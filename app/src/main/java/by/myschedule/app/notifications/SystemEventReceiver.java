package by.myschedule.app.notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import by.myschedule.app.email.BackgroundSyncScheduler;

public final class SystemEventReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        NotificationHelper.ensureChannels(context);
        HomeworkReminderScheduler.scheduleNext(context);
        BackgroundSyncScheduler.update(context);
    }
}
