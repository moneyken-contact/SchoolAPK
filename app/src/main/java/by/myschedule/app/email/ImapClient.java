package by.myschedule.app.email;

import android.text.Html;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import by.myschedule.app.logic.SubjectMatcher;
import by.myschedule.app.model.EmailSettings;

public final class ImapClient {
    private static final int TIMEOUT_MILLIS = 20_000;
    private static final int INITIAL_MESSAGE_LIMIT = 50;
    private static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;
    private static final Pattern LITERAL_LENGTH = Pattern.compile("\\{(\\d+)}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODED_WORD = Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=");
    private static final Pattern BOUNDARY = Pattern.compile(
            "boundary\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CHARSET = Pattern.compile(
            "charset\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))",
            Pattern.CASE_INSENSITIVE
    );

    public SyncResult sync(EmailSettings settings, String password, long lastUid) throws Exception {
        if (!settings.isReady(password)) {
            throw new IllegalArgumentException("Заполните и включите настройки почты");
        }

        SSLSocket socket = null;
        try {
            socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(settings.host, settings.port);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.startHandshake();

            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            String greeting = readLine(input);
            if (!greeting.toUpperCase(Locale.ROOT).startsWith("* OK")) {
                throw new IOException("Почтовый сервер не принял соединение");
            }

            ensureOk(sendCommand(input, output, "A001", "LOGIN "
                    + quote(settings.username) + " " + quote(password)), "A001");
            ensureOk(sendCommand(input, output, "A002", "SELECT INBOX"), "A002");

            String searchQuery = lastUid > 0 ? "UID SEARCH UID " + (lastUid + 1) + ":*" : "UID SEARCH ALL";
            TaggedResponse search = sendCommand(input, output, "A003", searchQuery);
            ensureOk(search, "A003");
            List<Long> allUids = parseSearchUids(search.lines);
            allUids.removeIf(uid -> uid <= lastUid);
            Collections.sort(allUids);

            long highestUid = lastUid;
            for (long uid : allUids) highestUid = Math.max(highestUid, uid);

            List<Long> selectedUids = allUids;
            if (lastUid == 0 && allUids.size() > INITIAL_MESSAGE_LIMIT) {
                selectedUids = new ArrayList<>(allUids.subList(allUids.size() - INITIAL_MESSAGE_LIMIT, allUids.size()));
            }

            List<IncomingMessage> messages = new ArrayList<>();
            int commandNumber = 10;
            for (long uid : selectedUids) {
                String tag = String.format(Locale.ROOT, "A%03d", commandNumber++);
                TaggedResponse fetched = sendCommand(input, output, tag, "UID FETCH " + uid + " BODY.PEEK[]");
                ensureOk(fetched, tag);
                for (byte[] rawMessage : fetched.literals) {
                    if (rawMessage.length == 0) continue;
                    IncomingMessage parsed = parseMessage(settings.username, uid, rawMessage);
                    if (parsed != null && !parsed.body.trim().isEmpty()) messages.add(parsed);
                }
            }

            try {
                sendCommand(input, output, "A999", "LOGOUT");
            } catch (IOException ignored) {
                // The useful work is already complete.
            }
            return new SyncResult(messages, highestUid, selectedUids.size());
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Nothing else to release.
                }
            }
        }
    }

    private IncomingMessage parseMessage(String account, long uid, byte[] rawMessage) {
        MessageParts message = splitHeadersAndBody(rawMessage);
        Map<String, String> headers = parseHeaders(message.headers);
        String decodedSubject = decodeHeader(headers.getOrDefault("subject", ""));
        String homeworkSubject = SubjectMatcher.extractSubject(decodedSubject);
        if (homeworkSubject == null) return null;

        String body = extractText(headers, message.body).trim();
        if (body.isEmpty()) return null;
        String messageId = headers.getOrDefault("message-id", "").trim();
        String messageKey = account.toLowerCase(Locale.ROOT) + ":"
                + (messageId.isEmpty() ? "uid-" + uid : messageId);
        return new IncomingMessage(uid, messageKey, homeworkSubject, body);
    }

    private String extractText(Map<String, String> headers, byte[] encodedBody) {
        String contentType = headers.getOrDefault("content-type", "text/plain");
        String disposition = headers.getOrDefault("content-disposition", "");
        if (disposition.toLowerCase(Locale.ROOT).contains("attachment")) return "";

        if (contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            String boundary = findParameter(BOUNDARY, contentType);
            if (boundary.isEmpty()) return "";
            String raw = new String(encodedBody, StandardCharsets.ISO_8859_1);
            String[] segments = raw.split(Pattern.quote("--" + boundary));
            String htmlFallback = "";
            for (String segment : segments) {
                if (segment.trim().isEmpty() || segment.trim().equals("--")) continue;
                byte[] partBytes = segment.getBytes(StandardCharsets.ISO_8859_1);
                MessageParts part = splitHeadersAndBody(partBytes);
                Map<String, String> partHeaders = parseHeaders(part.headers);
                String partType = partHeaders.getOrDefault("content-type", "text/plain").toLowerCase(Locale.ROOT);
                String text = extractText(partHeaders, part.body).trim();
                if (text.isEmpty()) continue;
                if (partType.startsWith("text/plain")) return cleanText(text);
                if (partType.startsWith("text/html") && htmlFallback.isEmpty()) htmlFallback = text;
                if (partType.startsWith("multipart/") && htmlFallback.isEmpty()) htmlFallback = text;
            }
            return cleanText(htmlFallback);
        }

        String transferEncoding = headers.getOrDefault("content-transfer-encoding", "").trim();
        byte[] decodedBody = decodeTransfer(encodedBody, transferEncoding);
        String charsetName = findParameter(CHARSET, contentType);
        Charset charset = safeCharset(charsetName);
        String text = new String(decodedBody, charset);
        if (contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
            text = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString();
        }
        return cleanText(text);
    }

    private static TaggedResponse sendCommand(InputStream input, OutputStream output,
                                               String tag, String command) throws IOException {
        String line = tag + " " + command + "\r\n";
        output.write(line.getBytes(StandardCharsets.UTF_8));
        output.flush();

        List<String> lines = new ArrayList<>();
        List<byte[]> literals = new ArrayList<>();
        while (true) {
            String responseLine = readLine(input);
            lines.add(responseLine);

            Matcher matcher = LITERAL_LENGTH.matcher(responseLine.trim());
            if (matcher.find()) {
                int length;
                try {
                    length = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException error) {
                    throw new IOException("Некорректный ответ почтового сервера", error);
                }
                literals.add(readLiteral(input, length));
            }

            if (responseLine.regionMatches(true, 0, tag + " ", 0, tag.length() + 1)) break;
        }
        return new TaggedResponse(lines, literals);
    }

    private static void ensureOk(TaggedResponse response, String tag) throws IOException {
        for (int index = response.lines.size() - 1; index >= 0; index--) {
            String line = response.lines.get(index);
            if (line.regionMatches(true, 0, tag + " ", 0, tag.length() + 1)) {
                String upper = line.toUpperCase(Locale.ROOT);
                if (upper.startsWith((tag + " OK").toUpperCase(Locale.ROOT))) return;
                if (upper.contains("AUTHENTICATIONFAILED") || upper.contains("AUTHENTICATION FAILED")) {
                    throw new IOException("Почта отклонила логин или пароль приложения");
                }
                throw new IOException("Почтовый сервер вернул ошибку: " + compactServerMessage(line, tag));
            }
        }
        throw new IOException("Почтовый сервер не завершил ответ");
    }

    private static String compactServerMessage(String line, String tag) {
        String message = line.substring(Math.min(line.length(), tag.length())).trim();
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    private static List<Long> parseSearchUids(List<String> lines) {
        List<Long> result = new ArrayList<>();
        for (String line : lines) {
            if (!line.toUpperCase(Locale.ROOT).startsWith("* SEARCH")) continue;
            String[] values = line.substring(8).trim().split("\\s+");
            for (String value : values) {
                if (value.isEmpty()) continue;
                try {
                    result.add(Long.parseLong(value));
                } catch (NumberFormatException ignored) {
                    // Ignore non-UID response fragments.
                }
            }
        }
        return result;
    }

    private static MessageParts splitHeadersAndBody(byte[] bytes) {
        int split = indexOf(bytes, new byte[]{'\r', '\n', '\r', '\n'});
        int separatorLength = 4;
        if (split < 0) {
            split = indexOf(bytes, new byte[]{'\n', '\n'});
            separatorLength = 2;
        }
        if (split < 0) return new MessageParts(bytes, new byte[0]);
        return new MessageParts(
                Arrays.copyOfRange(bytes, 0, split),
                Arrays.copyOfRange(bytes, split + separatorLength, bytes.length)
        );
    }

    private static Map<String, String> parseHeaders(byte[] rawHeaders) {
        String text = new String(rawHeaders, StandardCharsets.ISO_8859_1)
                .replaceAll("\\r?\\n[ \\t]+", " ");
        Map<String, String> result = new HashMap<>();
        for (String line : text.split("\\r?\\n")) {
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            String key = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if (result.containsKey(key)) result.put(key, result.get(key) + ", " + value);
            else result.put(key, value);
        }
        return result;
    }

    private static String decodeHeader(String value) {
        Matcher matcher = ENCODED_WORD.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String charsetName = matcher.group(1);
            String encoding = matcher.group(2);
            String encoded = matcher.group(3);
            byte[] bytes;
            try {
                if (encoding.equalsIgnoreCase("B")) {
                    bytes = Base64.decode(encoded, Base64.DEFAULT);
                } else {
                    bytes = decodeQuotedPrintable(encoded.replace('_', ' ').getBytes(StandardCharsets.US_ASCII));
                }
                String decoded = new String(bytes, safeCharset(charsetName));
                matcher.appendReplacement(result, Matcher.quoteReplacement(decoded));
            } catch (IllegalArgumentException error) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(result);
        return result.toString().replaceAll("(?<=\\S)\\s+(?==\\?)", "").trim();
    }

    private static byte[] decodeTransfer(byte[] value, String encoding) {
        try {
            if (encoding.equalsIgnoreCase("base64")) return Base64.decode(value, Base64.DEFAULT);
            if (encoding.equalsIgnoreCase("quoted-printable")) return decodeQuotedPrintable(value);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
        return value;
    }

    private static byte[] decodeQuotedPrintable(byte[] value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(value.length);
        for (int index = 0; index < value.length; index++) {
            int current = value[index] & 0xFF;
            if (current == '=' && index + 1 < value.length) {
                if (value[index + 1] == '\r' && index + 2 < value.length && value[index + 2] == '\n') {
                    index += 2;
                    continue;
                }
                if (value[index + 1] == '\n') {
                    index += 1;
                    continue;
                }
                if (index + 2 < value.length) {
                    int high = Character.digit((char) value[index + 1], 16);
                    int low = Character.digit((char) value[index + 2], 16);
                    if (high >= 0 && low >= 0) {
                        output.write((high << 4) + low);
                        index += 2;
                        continue;
                    }
                }
            }
            output.write(current);
        }
        return output.toByteArray();
    }

    private static String findParameter(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) return "";
        String result = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return result == null ? "" : result.trim();
    }

    private static Charset safeCharset(String name) {
        if (name == null || name.trim().isEmpty()) return StandardCharsets.UTF_8;
        try {
            return Charset.forName(name.trim().replace("\"", ""));
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String cleanText(String value) {
        return value.replace("\u0000", "")
                .replaceAll("[ \\t]+\\r?\\n", "\n")
                .replaceAll("\\r?\\n{3,}", "\n\n")
                .trim();
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        safe = safe.replace("\r", "").replace("\n", "");
        return "\"" + safe + "\"";
    }

    private static int indexOf(byte[] source, byte[] target) {
        outer:
        for (int index = 0; index <= source.length - target.length; index++) {
            for (int offset = 0; offset < target.length; offset++) {
                if (source[index + offset] != target[offset]) continue outer;
            }
            return index;
        }
        return -1;
    }

    private static byte[] readLiteral(InputStream input, int length) throws IOException {
        if (length < 0) throw new IOException("Некорректный размер письма");
        if (length > MAX_MESSAGE_BYTES) {
            byte[] buffer = new byte[8192];
            int remaining = length;
            while (remaining > 0) {
                int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) throw new EOFException("Письмо передано не полностью");
                remaining -= read;
            }
            return new byte[0];
        }
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(result, offset, length - offset);
            if (read < 0) throw new EOFException("Письмо передано не полностью");
            offset += read;
        }
        return result;
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous = -1;
        while (buffer.size() < 65_536) {
            int current = input.read();
            if (current < 0) throw new EOFException("Почтовый сервер закрыл соединение");
            if (previous == '\r' && current == '\n') {
                byte[] bytes = buffer.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.ISO_8859_1);
            }
            buffer.write(current);
            previous = current;
        }
        throw new IOException("Слишком длинная строка в ответе почтового сервера");
    }

    public static final class IncomingMessage {
        public final long uid;
        public final String messageKey;
        public final String subject;
        public final String body;

        private IncomingMessage(long uid, String messageKey, String subject, String body) {
            this.uid = uid;
            this.messageKey = messageKey;
            this.subject = subject;
            this.body = body;
        }
    }

    public static final class SyncResult {
        public final List<IncomingMessage> messages;
        public final long highestUid;
        public final int scannedMessages;

        private SyncResult(List<IncomingMessage> messages, long highestUid, int scannedMessages) {
            this.messages = messages;
            this.highestUid = highestUid;
            this.scannedMessages = scannedMessages;
        }
    }

    private static final class TaggedResponse {
        private final List<String> lines;
        private final List<byte[]> literals;

        private TaggedResponse(List<String> lines, List<byte[]> literals) {
            this.lines = lines;
            this.literals = literals;
        }
    }

    private static final class MessageParts {
        private final byte[] headers;
        private final byte[] body;

        private MessageParts(byte[] headers, byte[] body) {
            this.headers = headers;
            this.body = body;
        }
    }
}
