package by.myschedule.app.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import by.myschedule.app.MainActivity;
import by.myschedule.app.R;
import by.myschedule.app.data.AppRepository;
import by.myschedule.app.logic.BellSchedule;
import by.myschedule.app.logic.HolidayCalendar;
import by.myschedule.app.logic.LessonTimeline;
import by.myschedule.app.logic.RoomFormatter;
import by.myschedule.app.logic.SchedulePolicy;
import by.myschedule.app.model.Homework;
import by.myschedule.app.model.HomeworkStatus;
import by.myschedule.app.model.LessonBlock;
import by.myschedule.app.ui.UiKit;

public final class SchoolWidgetProvider extends AppWidgetProvider {
    private static final Locale RUSSIAN = new Locale("ru", "RU");
    private static final DateTimeFormatter WIDGET_DATE =
            DateTimeFormatter.ofPattern("EEEE, d MMMM", RUSSIAN);

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            manager.updateAppWidget(appWidgetId, buildViews(context));
        }
    }

    public static void updateAll(Context context) {
        Context appContext = context.getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(appContext);
        ComponentName provider = new ComponentName(appContext, SchoolWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(provider);
        for (int id : ids) manager.updateAppWidget(id, buildViews(appContext));
    }

    private static RemoteViews buildViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_school);
        boolean darkTheme = UiKit.loadDarkTheme(context);
        views.setInt(R.id.widget_root, "setBackgroundResource", darkTheme
                ? R.drawable.widget_background_dark : R.drawable.widget_background);
        views.setTextColor(R.id.widget_context, context.getColor(darkTheme
                ? R.color.dark_accent : R.color.dusty_blue));
        views.setTextColor(R.id.widget_subject, context.getColor(darkTheme
                ? R.color.dark_text_primary : R.color.light_text_primary));
        int secondaryText = context.getColor(darkTheme
                ? R.color.dark_text_secondary : R.color.light_text_secondary);
        views.setTextColor(R.id.widget_details, secondaryText);
        views.setTextColor(R.id.widget_homework, secondaryText);
        AppRepository repository = new AppRepository(context);
        ZonedDateTime now = ZonedDateTime.now();
        LocalDate date = findPopulatedDate(repository, SchedulePolicy.scheduleDateFor(now));
        List<LessonBlock> lessons = repository.getLessonsForDay(date.getDayOfWeek().getValue());
        LessonTimeline.State timeline = LessonTimeline.evaluate(
                date, now.toLocalDate(), now.toLocalTime(), lessons);

        if (timeline.phase == LessonTimeline.Phase.FINISHED) {
            date = findPopulatedDate(repository, HolidayCalendar.nextStudyDate(date, false));
            lessons = repository.getLessonsForDay(date.getDayOfWeek().getValue());
            timeline = LessonTimeline.evaluate(date, now.toLocalDate(), LocalTime.MIDNIGHT, lessons);
        }

        LessonBlock lesson = timeline.phase == LessonTimeline.Phase.CURRENT_LESSON
                ? timeline.currentLesson : timeline.nextLesson;
        String contextLabel;
        switch (timeline.phase) {
            case CURRENT_LESSON:
                contextLabel = "Сейчас · до звонка " + timeline.minutes + " мин";
                break;
            case BREAK:
                contextLabel = "Перемена · до урока " + timeline.minutes + " мин";
                break;
            case NEXT_LESSON:
                contextLabel = "Следующий урок · через " + timeline.minutes + " мин";
                break;
            default:
                contextLabel = capitalize(date.format(WIDGET_DATE));
                break;
        }

        views.setTextViewText(R.id.widget_context, contextLabel);
        if (lesson == null) {
            views.setTextViewText(R.id.widget_subject, "Уроков нет");
            views.setTextViewText(R.id.widget_details, "Откройте School, чтобы проверить расписание");
        } else {
            views.setTextViewText(R.id.widget_subject, lesson.subject);
            views.setTextViewText(R.id.widget_details,
                    BellSchedule.blockTimeLabel(lesson.dayOfWeek, lesson.startPeriod, lesson.endPeriod)
                            + "  ·  " + RoomFormatter.compact(lesson.room));
        }

        int unfinished = 0;
        for (Homework homework : repository.getHomeworksForDate(date)) {
            if (homework.status != HomeworkStatus.DONE) unfinished++;
        }
        views.setTextViewText(R.id.widget_homework, unfinished == 0
                ? "Домашка выполнена или не добавлена"
                : "Невыполненная домашка: " + unfinished);

        Intent open = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_SCREEN, "schedule")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 401, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent);
        return views;
    }

    private static LocalDate findPopulatedDate(AppRepository repository, LocalDate start) {
        LocalDate candidate = start;
        for (int offset = 0; offset < 500; offset++) {
            if (HolidayCalendar.isStudyDay(candidate)
                    && !repository.getLessonsForDay(candidate.getDayOfWeek().getValue()).isEmpty()) {
                return candidate;
            }
            candidate = HolidayCalendar.nextStudyDate(candidate, false);
        }
        return start;
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) return value;
        return value.substring(0, 1).toUpperCase(RUSSIAN) + value.substring(1);
    }
}
