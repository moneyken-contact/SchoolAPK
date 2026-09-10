package by.myschedule.app.logic;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;

public final class HolidayCalendar {
    private static final LocalDate AUTUMN_START = LocalDate.of(2026, 11, 1);
    private static final LocalDate AUTUMN_END = LocalDate.of(2026, 11, 8);
    private static final LocalDate WINTER_START = LocalDate.of(2026, 12, 25);
    private static final LocalDate WINTER_END = LocalDate.of(2027, 1, 10);
    private static final LocalDate SPRING_START = LocalDate.of(2027, 3, 21);
    private static final LocalDate SPRING_END = LocalDate.of(2027, 3, 28);

    private HolidayCalendar() {
    }

    public static boolean isHoliday(LocalDate date) {
        return isWithin(date, AUTUMN_START, AUTUMN_END)
                || isWithin(date, WINTER_START, WINTER_END)
                || isWithin(date, SPRING_START, SPRING_END)
                || isSummerHoliday(date);
    }

    public static boolean isStudyDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SUNDAY && !isHoliday(date);
    }

    public static LocalDate nextStudyDate(LocalDate date, boolean includeDate) {
        LocalDate candidate = includeDate ? date : date.plusDays(1);
        while (!isStudyDay(candidate)) candidate = candidate.plusDays(1);
        return candidate;
    }

    public static String holidayName(LocalDate date) {
        if (isWithin(date, AUTUMN_START, AUTUMN_END)) return "Осенние каникулы";
        if (isWithin(date, WINTER_START, WINTER_END)) return "Зимние каникулы";
        if (isWithin(date, SPRING_START, SPRING_END)) return "Весенние каникулы";
        if (isSummerHoliday(date)) return "Летние каникулы";
        return "";
    }

    private static boolean isSummerHoliday(LocalDate date) {
        Month month = date.getMonth();
        return month == Month.JUNE || month == Month.JULY || month == Month.AUGUST;
    }

    private static boolean isWithin(LocalDate date, LocalDate start, LocalDate end) {
        return !date.isBefore(start) && !date.isAfter(end);
    }
}
