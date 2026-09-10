package by.myschedule.app.logic;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SubjectMatcher {
    private static final Pattern HOMEWORK_SUBJECT = Pattern.compile(
            "^(.+?)\\s*\\.\\s*домашка\\s*\\.\\s*домашка\\s*[;.]?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private SubjectMatcher() {
    }

    public static String extractSubject(String emailSubject) {
        if (emailSubject == null) return null;
        Matcher matcher = HOMEWORK_SUBJECT.matcher(emailSubject.trim());
        if (!matcher.matches()) return null;
        String result = matcher.group(1).trim();
        return result.isEmpty() ? null : result;
    }

    public static String findCanonical(String rawSubject, List<String> knownSubjects) {
        if (rawSubject == null || rawSubject.trim().isEmpty()) return "Без предмета";
        String normalizedRaw = normalize(rawSubject);

        for (String known : knownSubjects) {
            if (normalize(known).equals(normalizedRaw)) return known;
        }

        List<String> partialMatches = new ArrayList<>();
        for (String known : knownSubjects) {
            String normalizedKnown = normalize(known);
            if (normalizedKnown.contains(normalizedRaw) || normalizedRaw.contains(normalizedKnown)) {
                partialMatches.add(known);
            }
        }
        if (partialMatches.size() == 1) return partialMatches.get(0);

        return rawSubject.trim();
    }

    public static boolean sameSubject(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(new Locale("ru", "RU"))
                .replace('ё', 'е');
        return normalized.replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
