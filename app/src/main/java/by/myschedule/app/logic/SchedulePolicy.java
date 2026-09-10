package by.myschedule.app.logic;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

public final class SchedulePolicy {
    private static final LocalTime REGULAR_CUTOFF = LocalTime.of(17, 0);
    private static final LocalTime FRIDAY_CUTOFF = LocalTime.of(18, 0);

    private SchedulePolicy() {
    }

    public static LocalDate scheduleDateFor(ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        DayOfWeek day = today.getDayOfWeek();

        if (!HolidayCalendar.isStudyDay(today)) {
            return HolidayCalendar.nextStudyDate(today, false);
        }

        LocalTime cutoff = day == DayOfWeek.FRIDAY ? FRIDAY_CUTOFF : REGULAR_CUTOFF;
        if (!now.toLocalTime().isAfter(cutoff)) {
            return today;
        }
        return nextSchoolDate(today, false);
    }

    public static LocalDate nextHomeworkDate(LocalDate today) {
        return nextSchoolDate(today, false);
    }

    public static LocalDate nextSchoolDate(LocalDate date, boolean includeDate) {
        return HolidayCalendar.nextStudyDate(date, includeDate);
    }
}
