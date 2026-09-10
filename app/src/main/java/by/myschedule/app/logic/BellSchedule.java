package by.myschedule.app.logic;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class BellSchedule {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private static final PeriodTime[] MONDAY_TUESDAY_FRIDAY = periods(
            "08:30", "09:15",
            "09:25", "10:10",
            "10:25", "11:10",
            "11:25", "12:10",
            "12:25", "13:10",
            "13:25", "14:10",
            "14:30", "15:15",
            "15:25", "16:10"
    );

    private static final PeriodTime[] WEDNESDAY = periods(
            "08:30", "09:15",
            "09:25", "10:10",
            "10:20", "11:05",
            "11:20", "12:05",
            "12:20", "13:05",
            "13:20", "14:05",
            "14:45", "15:30",
            "15:40", "16:25"
    );

    private static final PeriodTime[] THURSDAY = periods(
            "09:05", "09:50",
            "10:00", "10:45",
            "11:00", "11:45",
            "12:00", "12:45",
            "13:00", "13:45",
            "14:10", "14:55",
            "15:10", "15:55",
            "16:00", "16:45"
    );

    private BellSchedule() {
    }

    public static PeriodTime period(int dayOfWeek, int periodNumber) {
        PeriodTime[] schedule;
        if (dayOfWeek == DayOfWeek.WEDNESDAY.getValue()) {
            schedule = WEDNESDAY;
        } else if (dayOfWeek == DayOfWeek.THURSDAY.getValue()) {
            schedule = THURSDAY;
        } else {
            schedule = MONDAY_TUESDAY_FRIDAY;
        }
        int safeIndex = Math.max(1, Math.min(periodNumber, schedule.length)) - 1;
        return schedule[safeIndex];
    }

    public static String blockTimeLabel(int dayOfWeek, int startPeriod, int endPeriod) {
        if (startPeriod == endPeriod) {
            PeriodTime only = period(dayOfWeek, startPeriod);
            return only.start.format(TIME_FORMAT) + "–" + only.end.format(TIME_FORMAT);
        }
        StringBuilder label = new StringBuilder();
        for (int number = startPeriod; number <= endPeriod; number++) {
            if (label.length() > 0) label.append(" · ");
            PeriodTime item = period(dayOfWeek, number);
            label.append(item.start.format(TIME_FORMAT)).append("–").append(item.end.format(TIME_FORMAT));
        }
        return label.toString();
    }

    public static int breakMinutes(int dayOfWeek, int previousEndPeriod, int nextStartPeriod) {
        LocalTime end = period(dayOfWeek, previousEndPeriod).end;
        LocalTime start = period(dayOfWeek, nextStartPeriod).start;
        return (int) Math.max(0, Duration.between(end, start).toMinutes());
    }

    public static String breakTimeLabel(int dayOfWeek, int previousEndPeriod, int nextStartPeriod) {
        LocalTime end = period(dayOfWeek, previousEndPeriod).end;
        LocalTime start = period(dayOfWeek, nextStartPeriod).start;
        return end.format(TIME_FORMAT) + "–" + start.format(TIME_FORMAT);
    }

    private static PeriodTime[] periods(String... values) {
        PeriodTime[] result = new PeriodTime[values.length / 2];
        for (int index = 0; index < values.length; index += 2) {
            result[index / 2] = new PeriodTime(LocalTime.parse(values[index]), LocalTime.parse(values[index + 1]));
        }
        return result;
    }

    public static final class PeriodTime {
        public final LocalTime start;
        public final LocalTime end;

        private PeriodTime(LocalTime start, LocalTime end) {
            this.start = start;
            this.end = end;
        }
    }
}
