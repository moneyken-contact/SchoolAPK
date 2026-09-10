package by.myschedule.app.data;

import by.myschedule.app.model.LessonBlock;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DefaultSchedule {
    private DefaultSchedule() {
    }

    public static List<LessonBlock> create() {
        List<LessonBlock> lessons = new ArrayList<>();

        add(lessons, DayOfWeek.MONDAY, 1, 2, "Белорусский язык", "226");
        add(lessons, DayOfWeek.MONDAY, 3, 3, "Английский язык", "222");
        add(lessons, DayOfWeek.MONDAY, 4, 4, "Обществоведение", "108");
        add(lessons, DayOfWeek.MONDAY, 5, 6, "Физика", "333");
        add(lessons, DayOfWeek.MONDAY, 7, 7, "Алгебра", "107");

        add(lessons, DayOfWeek.TUESDAY, 1, 2, "География", "330");
        add(lessons, DayOfWeek.TUESDAY, 3, 4, "Физкультура", "С.з. 2");
        add(lessons, DayOfWeek.TUESDAY, 5, 6, "Алгебра", "107");
        add(lessons, DayOfWeek.TUESDAY, 7, 7, "ВОВ", "108");

        add(lessons, DayOfWeek.WEDNESDAY, 1, 1, "Физика", "333");
        add(lessons, DayOfWeek.WEDNESDAY, 2, 2, "Русская литература", "221");
        add(lessons, DayOfWeek.WEDNESDAY, 3, 3, "Русская литература", "220");
        add(lessons, DayOfWeek.WEDNESDAY, 4, 4, "Биология", "210");
        add(lessons, DayOfWeek.WEDNESDAY, 5, 6, "Английский язык", "222");

        add(lessons, DayOfWeek.THURSDAY, 1, 2, "Химия", "336");
        add(lessons, DayOfWeek.THURSDAY, 3, 4, "Русский язык", "220");
        add(lessons, DayOfWeek.THURSDAY, 5, 5, "Искусство", "108");
        add(lessons, DayOfWeek.THURSDAY, 6, 6, "Информатика", "224");

        add(lessons, DayOfWeek.FRIDAY, 1, 1, "Труды", "21");
        add(lessons, DayOfWeek.FRIDAY, 2, 2, "Белорусская литература", "214");
        add(lessons, DayOfWeek.FRIDAY, 3, 4, "История", "108");
        add(lessons, DayOfWeek.FRIDAY, 5, 6, "Геометрия", "107");
        add(lessons, DayOfWeek.FRIDAY, 7, 7, "Физкультура");

        add(lessons, DayOfWeek.SATURDAY, 1, 2, "Физика", "330");

        return lessons;
    }

    private static void add(List<LessonBlock> target, DayOfWeek day, int start, int end, String subject) {
        add(target, day, start, end, subject, "");
    }

    private static void add(List<LessonBlock> target, DayOfWeek day, int start, int end,
                            String subject, String room) {
        for (int period = start; period <= end; period++) {
            target.add(new LessonBlock(
                    day.name().toLowerCase(Locale.ROOT) + "-" + period,
                    day.getValue(),
                    period,
                    period,
                    subject,
                    room
            ));
        }
    }
}
