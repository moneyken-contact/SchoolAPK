package by.myschedule.app.notifications;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.time.ZonedDateTime;

public final class HomeworkReminderScheduler {
    private static final int REQUEST_CODE = 3_019;
    private static final int REMINDER_HOUR = 19;

    private HomeworkReminderScheduler() {
    }

    public static void scheduleNext(Context context) {
        Context applicationContext = context.getApplicationContext();
        AlarmManager manager = (AlarmManager) applicationContext.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;

        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = now.withHour(REMINDER_HOUR).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                next.toInstant().toEpochMilli(), reminderIntent(applicationContext));
    }

    private static PendingIntent reminderIntent(Context context) {
        Intent intent = new Intent(context, HomeworkReminderReceiver.class);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
