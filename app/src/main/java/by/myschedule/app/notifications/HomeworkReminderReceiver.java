package by.myschedule.app.notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import by.myschedule.app.data.AppRepository;
import by.myschedule.app.logic.SchedulePolicy;
import by.myschedule.app.model.Homework;
import by.myschedule.app.model.HomeworkStatus;

public final class HomeworkReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AppRepository repository = new AppRepository(context);
        LocalDate nearestStudyDate = SchedulePolicy.nextHomeworkDate(LocalDate.now());
        List<Homework> unfinished = new ArrayList<>();
        for (Homework homework : repository.getHomeworks()) {
            if (homework.status != HomeworkStatus.DONE
                    && !homework.dueDate.isAfter(nearestStudyDate)) {
                unfinished.add(homework);
            }
        }
        NotificationHelper.showHomeworkReminder(context, unfinished);
        HomeworkReminderScheduler.scheduleNext(context);
    }
}
