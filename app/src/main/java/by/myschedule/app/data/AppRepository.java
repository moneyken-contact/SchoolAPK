package by.myschedule.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;

import by.myschedule.app.logic.SchedulePolicy;
import by.myschedule.app.logic.SubjectMatcher;
import by.myschedule.app.logic.HolidayCalendar;
import by.myschedule.app.logic.RoomAssignments;
import by.myschedule.app.model.EmailSettings;
import by.myschedule.app.model.Homework;
import by.myschedule.app.model.LessonBlock;
import by.myschedule.app.model.ReceivedAnswer;

public final class AppRepository {
    private static final String PREFS = "school_schedule_data";
    private static final String KEY_SCHEDULE = "schedule_v2";
    private static final String KEY_LEGACY_SCHEDULE = "schedule_v1";
    private static final String KEY_HOMEWORK = "homework_v1";
    private static final String KEY_ANSWERS = "answers_v1";
    private static final String KEY_EMAIL_ENABLED = "email_enabled";
    private static final String KEY_EMAIL_HOST = "email_host";
    private static final String KEY_EMAIL_PORT = "email_port";
    private static final String KEY_EMAIL_USER = "email_user";
    private static final String KEY_LAST_UID = "last_imap_uid";
    private static final String KEY_LAST_SYNC = "last_imap_sync";
    private static final String KEY_SCHEDULE_PATCH_VERSION = "schedule_patch_version";
    private static final int CURRENT_SCHEDULE_PATCH_VERSION = 2;

    private final SharedPreferences preferences;

    public AppRepository(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ensureDefaultSchedule();
        applySchedulePatch();
    }

    public synchronized List<LessonBlock> getSchedule() {
        List<LessonBlock> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_SCHEDULE, "[]"));
            for (int index = 0; index < array.length(); index++) {
                result.add(LessonBlock.fromJson(array.getJSONObject(index)));
            }
        } catch (JSONException error) {
            result = DefaultSchedule.create();
            saveSchedule(result);
        }
        result.sort(Comparator.comparingInt((LessonBlock item) -> item.dayOfWeek)
                .thenComparingInt(item -> item.startPeriod));
        return result;
    }

    public synchronized List<LessonBlock> getLessonsForDay(int dayOfWeek) {
        List<LessonBlock> result = new ArrayList<>();
        for (LessonBlock lesson : getSchedule()) {
            if (lesson.dayOfWeek == dayOfWeek && !lesson.subject.trim().isEmpty()) result.add(lesson);
        }
        result.sort(Comparator.comparingInt(item -> item.startPeriod));
        return result;
    }

    public synchronized void updateLesson(String id, String subject, String room) {
        List<LessonBlock> lessons = getSchedule();
        for (LessonBlock lesson : lessons) {
            if (lesson.id.equals(id)) {
                lesson.subject = subject == null ? "" : subject.trim();
                lesson.room = room == null ? "" : room.trim();
                break;
            }
        }
        saveSchedule(lessons);
    }

    public synchronized void resetSchedule() {
        saveSchedule(DefaultSchedule.create());
    }

    public synchronized List<String> getKnownSubjects() {
        Set<String> subjects = new LinkedHashSet<>();
        for (LessonBlock lesson : getSchedule()) {
            if (!lesson.subject.trim().isEmpty()) subjects.add(lesson.subject.trim());
        }
        return new ArrayList<>(subjects);
    }

    public synchronized List<Homework> getHomeworks() {
        List<Homework> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_HOMEWORK, "[]"));
            for (int index = 0; index < array.length(); index++) {
                result.add(Homework.fromJson(array.getJSONObject(index)));
            }
        } catch (JSONException ignored) {
            // Keep the rest of the app usable if local data from an older build is malformed.
        }
        result.sort(Comparator.comparing((Homework item) -> item.dueDate)
                .thenComparingLong(item -> item.createdAt));
        return result;
    }

    public synchronized void saveHomework(Homework homework) {
        List<Homework> items = getHomeworks();
        boolean replaced = false;
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).id.equals(homework.id)) {
                items.set(index, homework);
                replaced = true;
                break;
            }
        }
        if (!replaced) items.add(homework);
        saveHomeworks(items);
    }

    public synchronized void deleteHomework(String id) {
        List<Homework> items = getHomeworks();
        items.removeIf(item -> item.id.equals(id));
        saveHomeworks(items);
    }

    public synchronized List<Homework> getHomeworksForDate(LocalDate date) {
        List<Homework> result = new ArrayList<>();
        for (Homework item : getHomeworks()) {
            if (item.dueDate.equals(date)) result.add(item);
        }
        return result;
    }

    public synchronized List<ReceivedAnswer> getAnswers() {
        List<ReceivedAnswer> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_ANSWERS, "[]"));
            for (int index = 0; index < array.length(); index++) {
                result.add(ReceivedAnswer.fromJson(array.getJSONObject(index)));
            }
        } catch (JSONException ignored) {
            // Ignore only the unreadable answer list; schedule and homework remain intact.
        }
        result.sort(Comparator.comparing((ReceivedAnswer item) -> item.lessonDate)
                .thenComparing(Comparator.comparingLong((ReceivedAnswer item) -> item.receivedAt).reversed()));
        return result;
    }

    public synchronized boolean addIncomingAnswer(String messageKey, String rawSubject,
                                                   String body, LocalDate today) {
        List<ReceivedAnswer> answers = getAnswers();
        for (ReceivedAnswer existing : answers) {
            if (existing.messageKey.equals(messageKey)) return false;
        }

        String canonical = SubjectMatcher.findCanonical(rawSubject, getKnownSubjects());
        Homework linkedHomework = findClosestHomework(canonical, today);
        LocalDate lessonDate = linkedHomework == null
                ? findNextLessonDate(canonical, today)
                : linkedHomework.dueDate;
        String homeworkText = linkedHomework == null ? "" : linkedHomework.description;

        answers.add(new ReceivedAnswer(
                UUID.randomUUID().toString(),
                messageKey,
                canonical,
                body == null ? "" : body.trim(),
                homeworkText,
                lessonDate,
                System.currentTimeMillis()
        ));
        saveAnswers(answers);
        return true;
    }

    public synchronized EmailSettings getEmailSettings() {
        return new EmailSettings(
                preferences.getBoolean(KEY_EMAIL_ENABLED, true),
                preferences.getString(KEY_EMAIL_HOST, "imap.gmail.com"),
                preferences.getInt(KEY_EMAIL_PORT, 993),
                preferences.getString(KEY_EMAIL_USER, "timoffi945@gmail.com")
        );
    }

    public synchronized void saveEmailSettings(EmailSettings settings) {
        EmailSettings previous = getEmailSettings();
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(KEY_EMAIL_ENABLED, settings.enabled)
                .putString(KEY_EMAIL_HOST, settings.host)
                .putInt(KEY_EMAIL_PORT, settings.port)
                .putString(KEY_EMAIL_USER, settings.username);
        if (!previous.host.equalsIgnoreCase(settings.host)
                || !previous.username.equalsIgnoreCase(settings.username)) {
            editor.putLong(KEY_LAST_UID, 0L).putLong(KEY_LAST_SYNC, 0L);
        }
        editor.apply();
    }

    public long getLastImapUid() {
        return preferences.getLong(KEY_LAST_UID, 0L);
    }

    public void setLastImapUid(long uid) {
        preferences.edit().putLong(KEY_LAST_UID, uid).apply();
    }

    public long getLastSyncAt() {
        return preferences.getLong(KEY_LAST_SYNC, 0L);
    }

    public void setLastSyncAt(long timestamp) {
        preferences.edit().putLong(KEY_LAST_SYNC, timestamp).apply();
    }

    public synchronized String createBackupJson() throws JSONException {
        JSONObject backup = new JSONObject();
        backup.put("formatVersion", 1);
        backup.put("application", "School");
        backup.put("exportedAt", System.currentTimeMillis());

        JSONArray schedule = new JSONArray();
        for (LessonBlock lesson : getSchedule()) schedule.put(lesson.toJson());
        backup.put("schedule", schedule);

        JSONArray homework = new JSONArray();
        for (Homework item : getHomeworks()) homework.put(item.toJson());
        backup.put("homework", homework);

        JSONArray answers = new JSONArray();
        for (ReceivedAnswer answer : getAnswers()) answers.put(answer.toJson());
        backup.put("answers", answers);

        EmailSettings email = getEmailSettings();
        backup.put("email", new JSONObject()
                .put("host", email.host)
                .put("port", email.port)
                .put("username", email.username));
        return backup.toString(2);
    }

    public synchronized void restoreBackup(String json) throws Exception {
        JSONObject backup = new JSONObject(json);
        if (backup.optInt("formatVersion", 0) != 1) {
            throw new IllegalArgumentException("Неподдерживаемый формат резервной копии");
        }

        JSONArray scheduleJson = backup.getJSONArray("schedule");
        JSONArray homeworkJson = backup.getJSONArray("homework");
        JSONArray answersJson = backup.getJSONArray("answers");
        List<LessonBlock> schedule = new ArrayList<>();
        List<Homework> homework = new ArrayList<>();
        List<ReceivedAnswer> answers = new ArrayList<>();
        for (int index = 0; index < scheduleJson.length(); index++) {
            schedule.add(LessonBlock.fromJson(scheduleJson.getJSONObject(index)));
        }
        for (int index = 0; index < homeworkJson.length(); index++) {
            homework.add(Homework.fromJson(homeworkJson.getJSONObject(index)));
        }
        for (int index = 0; index < answersJson.length(); index++) {
            answers.add(ReceivedAnswer.fromJson(answersJson.getJSONObject(index)));
        }
        if (schedule.isEmpty()) throw new IllegalArgumentException("В копии нет расписания");

        JSONObject email = backup.optJSONObject("email");
        String host = email == null ? "imap.gmail.com" : email.optString("host", "imap.gmail.com");
        int port = email == null ? 993 : email.optInt("port", 993);
        String username = email == null ? "" : email.optString("username", "");

        saveSchedule(schedule);
        saveHomeworks(homework);
        saveAnswers(answers);
        preferences.edit()
                .putBoolean(KEY_EMAIL_ENABLED, false)
                .putString(KEY_EMAIL_HOST, host)
                .putInt(KEY_EMAIL_PORT, port)
                .putString(KEY_EMAIL_USER, username)
                .putLong(KEY_LAST_UID, 0L)
                .putLong(KEY_LAST_SYNC, 0L)
                .putInt(KEY_SCHEDULE_PATCH_VERSION, CURRENT_SCHEDULE_PATCH_VERSION)
                .apply();
    }

    private Homework findClosestHomework(String subject, LocalDate today) {
        for (Homework item : getHomeworks()) {
            if (!item.dueDate.isBefore(today) && SubjectMatcher.sameSubject(item.subject, subject)) {
                return item;
            }
        }
        return null;
    }

    private LocalDate findNextLessonDate(String subject, LocalDate today) {
        LocalDate candidate = today;
        for (int offset = 0; offset < 500; offset++) {
            if (HolidayCalendar.isStudyDay(candidate)) {
                for (LessonBlock lesson : getLessonsForDay(candidate.getDayOfWeek().getValue())) {
                    if (SubjectMatcher.sameSubject(lesson.subject, subject)) return candidate;
                }
            }
            candidate = candidate.plusDays(1);
        }
        return SchedulePolicy.nextHomeworkDate(today);
    }

    private void ensureDefaultSchedule() {
        if (preferences.contains(KEY_SCHEDULE)) return;
        if (preferences.contains(KEY_LEGACY_SCHEDULE) && migrateLegacySchedule()) return;
        saveSchedule(DefaultSchedule.create());
    }

    private void applySchedulePatch() {
        if (preferences.getInt(KEY_SCHEDULE_PATCH_VERSION, 0) >= CURRENT_SCHEDULE_PATCH_VERSION) return;

        List<LessonBlock> lessons = getSchedule();
        for (LessonBlock lesson : lessons) {
            String requiredRoom = RoomAssignments.forSlot(lesson.dayOfWeek, lesson.startPeriod);
            if (requiredRoom == null) requiredRoom = requiredRoomForSubject(lesson.subject);
            if (requiredRoom != null) lesson.room = requiredRoom;
        }
        upsertSaturdayPhysics(lessons, 1);
        upsertSaturdayPhysics(lessons, 2);
        saveSchedule(lessons);
        preferences.edit()
                .putInt(KEY_SCHEDULE_PATCH_VERSION, CURRENT_SCHEDULE_PATCH_VERSION)
                .apply();
    }

    private String requiredRoomForSubject(String subject) {
        if (SubjectMatcher.sameSubject(subject, "Обществоведение")
                || SubjectMatcher.sameSubject(subject, "История")
                || SubjectMatcher.sameSubject(subject, "Искусство")) {
            return "108";
        }
        if (SubjectMatcher.sameSubject(subject, "Алгебра")
                || SubjectMatcher.sameSubject(subject, "Геометрия")) {
            return "107";
        }
        return null;
    }

    private void upsertSaturdayPhysics(List<LessonBlock> lessons, int period) {
        for (LessonBlock lesson : lessons) {
            if (lesson.dayOfWeek == DayOfWeek.SATURDAY.getValue()
                    && lesson.startPeriod == period && lesson.endPeriod == period) {
                lesson.subject = "Физика";
                lesson.room = "330";
                return;
            }
        }
        lessons.add(new LessonBlock("saturday-" + period,
                DayOfWeek.SATURDAY.getValue(), period, period, "Физика", "330"));
    }

    private boolean migrateLegacySchedule() {
        try {
            JSONArray legacy = new JSONArray(preferences.getString(KEY_LEGACY_SCHEDULE, "[]"));
            List<LessonBlock> migrated = new ArrayList<>();
            for (int index = 0; index < legacy.length(); index++) {
                LessonBlock old = LessonBlock.fromJson(legacy.getJSONObject(index));
                for (int period = old.startPeriod; period <= old.endPeriod; period++) {
                    String day = DayOfWeek.of(old.dayOfWeek).name().toLowerCase(Locale.ROOT);
                    migrated.add(new LessonBlock(day + "-" + period, old.dayOfWeek,
                            period, period, old.subject, old.room));
                }
            }
            if (migrated.isEmpty()) return false;
            saveSchedule(migrated);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void saveSchedule(List<LessonBlock> lessons) {
        JSONArray array = new JSONArray();
        for (LessonBlock lesson : lessons) {
            try {
                array.put(lesson.toJson());
            } catch (JSONException ignored) {
                // All lesson values are primitives, so this should never occur.
            }
        }
        preferences.edit().putString(KEY_SCHEDULE, array.toString()).apply();
    }

    private void saveHomeworks(List<Homework> items) {
        JSONArray array = new JSONArray();
        for (Homework item : items) {
            try {
                array.put(item.toJson());
            } catch (JSONException ignored) {
                // All homework values are primitives, so this should never occur.
            }
        }
        preferences.edit().putString(KEY_HOMEWORK, array.toString()).apply();
    }

    private void saveAnswers(List<ReceivedAnswer> items) {
        JSONArray array = new JSONArray();
        for (ReceivedAnswer item : items) {
            try {
                array.put(item.toJson());
            } catch (JSONException ignored) {
                // All answer values are primitives, so this should never occur.
            }
        }
        preferences.edit().putString(KEY_ANSWERS, array.toString()).apply();
    }
}
