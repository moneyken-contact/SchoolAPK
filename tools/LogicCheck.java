import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

import by.myschedule.app.data.DefaultSchedule;
import by.myschedule.app.logic.BellSchedule;
import by.myschedule.app.logic.HolidayCalendar;
import by.myschedule.app.logic.LessonTimeline;
import by.myschedule.app.logic.RoomAssignments;
import by.myschedule.app.logic.RoomFormatter;
import by.myschedule.app.logic.SchedulePolicy;
import by.myschedule.app.logic.SubjectMatcher;
import by.myschedule.app.model.LessonBlock;

public final class LogicCheck {
    private static final ZoneId MINSK = ZoneId.of("Europe/Minsk");
    private static int checks;

    public static void main(String[] args) {
        checkDate("Обычный день до границы",
                LocalDate.of(2026, 9, 1),
                at(2026, 9, 1, 16, 59));
        checkDate("Ровно 17:00 остаётся текущий день",
                LocalDate.of(2026, 9, 1),
                at(2026, 9, 1, 17, 0));
        checkDate("После 17:00 показывается следующий день",
                LocalDate.of(2026, 9, 2),
                at(2026, 9, 1, 17, 1));
        checkDate("Ровно 18:00 в пятницу остаётся пятница",
                LocalDate.of(2026, 9, 4),
                at(2026, 9, 4, 18, 0));
        checkDate("После 18:00 в пятницу показывается суббота",
                LocalDate.of(2026, 9, 5),
                at(2026, 9, 4, 18, 1));
        checkDate("В субботу до границы показывается суббота",
                LocalDate.of(2026, 9, 5),
                at(2026, 9, 5, 10, 0));
        checkDate("После границы в субботу показывается понедельник",
                LocalDate.of(2026, 9, 7),
                at(2026, 9, 5, 17, 1));
        checkDate("В воскресенье показывается понедельник",
                LocalDate.of(2026, 9, 7),
                at(2026, 9, 6, 10, 0));
        checkDate("Во время осенних каникул показывается ближайший учебный день",
                LocalDate.of(2026, 11, 9),
                at(2026, 11, 2, 10, 0));

        check(HolidayCalendar.isHoliday(LocalDate.of(2026, 11, 1)),
                "Первый день осенних каникул должен учитываться");
        check(HolidayCalendar.isHoliday(LocalDate.of(2026, 11, 8)),
                "Последний день осенних каникул должен учитываться");
        check(!HolidayCalendar.isHoliday(LocalDate.of(2026, 11, 9)),
                "После осенних каникул должны начаться учебные дни");
        check(HolidayCalendar.isHoliday(LocalDate.of(2027, 1, 10)),
                "Зимние каникулы должны включать 10 января");
        check(HolidayCalendar.isHoliday(LocalDate.of(2027, 3, 28)),
                "Весенние каникулы должны включать 28 марта 2027 года");
        check(!HolidayCalendar.isHoliday(LocalDate.of(2027, 3, 29)),
                "После весенних каникул должны начаться учебные дни");
        checkDate("Во время весенних каникул показывается 29 марта 2027 года",
                LocalDate.of(2027, 3, 29),
                at(2027, 3, 22, 10, 0));
        check(HolidayCalendar.isHoliday(LocalDate.of(2030, 7, 15)),
                "Летние каникулы должны повторяться ежегодно");
        check(!HolidayCalendar.isHoliday(LocalDate.of(2030, 9, 1)),
                "Сентябрь не должен считаться летними каникулами");

        check(SchedulePolicy.nextHomeworkDate(LocalDate.of(2026, 9, 4))
                        .equals(LocalDate.of(2026, 9, 5)),
                "Домашка пятницы должна переноситься на субботу");
        check("физика".equals(SubjectMatcher.extractSubject("физика.домашка.домашка")),
                "Тема письма должна распознаваться");
        check(SubjectMatcher.extractSubject("Обычное письмо") == null,
                "Обычное письмо не должно импортироваться");
        check("Английский язык".equals(SubjectMatcher.findCanonical("английский",
                        Arrays.asList("Английский язык", "Алгебра"))),
                "Короткое название предмета должно сопоставляться однозначно");
        check("литература".equals(SubjectMatcher.findCanonical("литература",
                        Arrays.asList("Русская литература", "Белорусская литература"))),
                "Неоднозначное название нельзя угадывать");
        check("09:05–09:50".equals(BellSchedule.blockTimeLabel(4, 1, 1)),
                "Первый звонок четверга должен совпадать с Excel");
        check(BellSchedule.breakMinutes(4, 5, 6) == 25,
                "Большая перемена четверга должна быть 25 минут");
        check(BellSchedule.breakMinutes(1, 1, 2) == 10,
                "Между первым и вторым уроками должна отображаться перемена");
        check(BellSchedule.blockTimeLabel(6, 1, 2)
                        .equals(BellSchedule.blockTimeLabel(1, 1, 2)),
                "Субботние звонки должны совпадать с понедельником");

        List<LessonBlock> lessons = DefaultSchedule.create();
        check(lessons.size() == 35, "Все пары должны быть развёрнуты в 35 отдельных уроков");
        check(lessons.stream().allMatch(lesson -> lesson.startPeriod == lesson.endPeriod),
                "Каждая карточка должна соответствовать ровно одному уроку");
        check(lessons.get(0).subject.equals(lessons.get(1).subject),
                "Два урока исходной пары должны сохранить один предмет");
        check(lessons.stream()
                        .filter(lesson -> lesson.dayOfWeek == 6)
                        .filter(lesson -> lesson.startPeriod == 1 || lesson.startPeriod == 2)
                        .filter(lesson -> lesson.subject.equals("Физика") && lesson.room.equals("330"))
                        .count() == 2,
                "В субботу первыми двумя уроками должна быть физика в кабинете 330");
        check(lessons.stream()
                        .filter(lesson -> SubjectMatcher.sameSubject(lesson.subject, "Обществоведение")
                                || SubjectMatcher.sameSubject(lesson.subject, "История")
                                || SubjectMatcher.sameSubject(lesson.subject, "Искусство"))
                        .allMatch(lesson -> lesson.room.equals("108")),
                "Обществоведение, история и искусство должны быть в кабинете 108");
        check(lessons.stream()
                        .filter(lesson -> SubjectMatcher.sameSubject(lesson.subject, "Алгебра")
                                || SubjectMatcher.sameSubject(lesson.subject, "Геометрия"))
                        .allMatch(lesson -> lesson.room.equals("107")),
                "Алгебра и геометрия должны быть в кабинете 107");

        Object[][] assignedRooms = {
                {1, 1, "226"}, {1, 2, "226"}, {1, 3, "222"}, {1, 4, "108"},
                {1, 5, "333"}, {1, 6, "333"}, {1, 7, "107"},
                {2, 1, "330"}, {2, 2, "330"}, {2, 3, "С.з. 2"}, {2, 4, "С.з. 2"},
                {2, 5, "107"}, {2, 6, "107"}, {2, 7, "108"},
                {3, 1, "333"}, {3, 2, "221"}, {3, 3, "220"}, {3, 4, "210"},
                {3, 5, "222"}, {3, 6, "222"},
                {4, 1, "336"}, {4, 2, "336"}, {4, 3, "220"}, {4, 4, "220"},
                {4, 5, "108"}, {4, 6, "224"},
                {5, 1, "21"}, {5, 2, "214"}, {5, 3, "108"}, {5, 4, "108"},
                {5, 5, "107"}, {5, 6, "107"},
                {6, 1, "330"}, {6, 2, "330"}
        };
        for (Object[] assignment : assignedRooms) {
            int day = (Integer) assignment[0];
            int period = (Integer) assignment[1];
            String expectedRoom = (String) assignment[2];
            check(expectedRoom.equals(RoomAssignments.forSlot(day, period)),
                    "Неверный кабинет для дня " + day + ", урока " + period);
            check(lessons.stream().anyMatch(lesson -> lesson.dayOfWeek == day
                            && lesson.startPeriod == period && expectedRoom.equals(lesson.room)),
                    "Исходное расписание должно содержать кабинет для дня " + day
                            + ", урока " + period);
        }
        check(RoomAssignments.forSlot(5, 7) == null,
                "Для седьмого урока пятницы кабинет не был указан");
        check("С.з. 2".equals(RoomFormatter.compact("С.з. 2")),
                "Спортивный зал не должен отображаться как кабинет");
        check("каб. 226".equals(RoomFormatter.compact("226")),
                "Обычный номер должен отображаться как кабинет");

        List<LessonBlock> timelineLessons = Arrays.asList(
                new LessonBlock("one", 1, 1, 1, "Алгебра", "107"),
                new LessonBlock("two", 1, 2, 2, "Физика", "330")
        );
        LocalDate monday = LocalDate.of(2026, 9, 7);
        LessonTimeline.State before = LessonTimeline.evaluate(
                monday, monday, LocalTime.of(8, 0), timelineLessons);
        check(before.phase == LessonTimeline.Phase.NEXT_LESSON
                        && before.nextLesson.id.equals("one") && before.minutes == 30,
                "До начала должен показываться следующий урок и обратный отсчёт");
        LessonTimeline.State current = LessonTimeline.evaluate(
                monday, monday, LocalTime.of(8, 40), timelineLessons);
        check(current.phase == LessonTimeline.Phase.CURRENT_LESSON
                        && current.currentLesson.id.equals("one") && current.minutes == 35,
                "Во время занятия должен определяться текущий урок");
        check(current.focusLesson() != null && current.focusLesson().id.equals("one"),
                "Во время занятия список должен фокусироваться на текущем уроке");
        LessonTimeline.State lessonBreak = LessonTimeline.evaluate(
                monday, monday, LocalTime.of(9, 20), timelineLessons);
        check(lessonBreak.phase == LessonTimeline.Phase.BREAK
                        && lessonBreak.nextLesson.id.equals("two") && lessonBreak.minutes == 5,
                "Между уроками должна определяться перемена");
        check(lessonBreak.focusLesson() != null && lessonBreak.focusLesson().id.equals("two"),
                "На перемене список должен фокусироваться на следующем уроке");
        check(!LessonTimeline.isFinished(monday, monday, LocalTime.of(9, 14),
                        timelineLessons.get(0)),
                "До звонка урок ещё не должен считаться завершённым");
        check(LessonTimeline.isFinished(monday, monday, LocalTime.of(9, 15),
                        timelineLessons.get(0)),
                "Сразу после звонка урок должен считаться завершённым");
        check(LessonTimeline.isFinished(monday, monday.plusDays(1), LocalTime.NOON,
                        timelineLessons.get(0)),
                "Урок прошедшего дня должен считаться завершённым");
        LessonTimeline.State future = LessonTimeline.evaluate(
                monday.plusDays(1), monday, LocalTime.NOON, timelineLessons);
        check(future.phase == LessonTimeline.Phase.UPCOMING_DAY,
                "Для будущей даты должен показываться ближайший урок без дневного таймера");
        check(!LessonTimeline.isFinished(monday.plusDays(1), monday, LocalTime.NOON,
                        timelineLessons.get(0)),
                "Урок будущего дня не должен считаться завершённым");

        System.out.println("Logic checks passed: " + checks);
    }

    private static ZonedDateTime at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(LocalDate.of(year, month, day), LocalTime.of(hour, minute), MINSK);
    }

    private static void checkDate(String label, LocalDate expected, ZonedDateTime input) {
        check(expected.equals(SchedulePolicy.scheduleDateFor(input)), label);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
