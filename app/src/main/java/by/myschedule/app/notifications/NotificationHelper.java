package by.myschedule.app.notifications;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import by.myschedule.app.MainActivity;
import by.myschedule.app.R;
import by.myschedule.app.model.Homework;
import by.myschedule.app.ui.UiKit;

public final class NotificationHelper {
    public static final String CHANNEL_HOMEWORK = "homework_reminders";
    public static final String CHANNEL_ANSWERS = "email_answers";
    private static final int HOMEWORK_NOTIFICATION_ID = 2_001;
    private static final int ANSWER_NOTIFICATION_ID = 2_002;

    private NotificationHelper() {
    }

    public static void ensureChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel homework = new NotificationChannel(
                CHANNEL_HOMEWORK, "Домашние задания", NotificationManager.IMPORTANCE_DEFAULT);
        homework.setDescription("Напоминания о невыполненных заданиях");
        NotificationChannel answers = new NotificationChannel(
                CHANNEL_ANSWERS, "Ответы по почте", NotificationManager.IMPORTANCE_HIGH);
        answers.setDescription("Новые ответы, полученные через почтовую синхронизацию");
        manager.createNotificationChannel(homework);
        manager.createNotificationChannel(answers);
    }

    public static void showHomeworkReminder(Context context, List<Homework> items) {
        if (items.isEmpty() || !canNotify(context)) return;
        ensureChannels(context);
        String subjects = uniqueSubjects(items);
        String text = items.size() == 1
                ? "Осталось задание: " + subjects
                : "Осталось заданий: " + items.size() + " · " + subjects;
        Notification notification = new Notification.Builder(context, CHANNEL_HOMEWORK)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setColor(UiKit.DUSTY_BLUE)
                .setContentTitle("Невыполненная домашка")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(openApp(context, "homework", 101))
                .build();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(HOMEWORK_NOTIFICATION_ID, notification);
    }

    public static void showNewAnswers(Context context, List<String> subjects) {
        if (subjects.isEmpty() || !canNotify(context)) return;
        ensureChannels(context);
        Set<String> unique = new LinkedHashSet<>(subjects);
        String joined = String.join(", ", unique);
        String title = subjects.size() == 1 ? "Получен новый ответ" : "Получены новые ответы";
        String text = subjects.size() == 1 ? joined : "Новых ответов: " + subjects.size() + " · " + joined;
        Notification notification = new Notification.Builder(context, CHANNEL_ANSWERS)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setColor(UiKit.DUSTY_BLUE)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_EMAIL)
                .setAutoCancel(true)
                .setContentIntent(openApp(context, "answers", 102))
                .build();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(ANSWER_NOTIFICATION_ID, notification);
    }

    private static boolean canNotify(Context context) {
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static PendingIntent openApp(Context context, String screen, int requestCode) {
        Intent intent = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_SCREEN, screen)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String uniqueSubjects(List<Homework> items) {
        Set<String> subjects = new LinkedHashSet<>();
        for (Homework item : items) {
            if (!item.subject.trim().isEmpty()) subjects.add(item.subject.trim());
            if (subjects.size() == 3) break;
        }
        return String.join(", ", subjects);
    }
}
