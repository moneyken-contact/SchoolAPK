package by.myschedule.app.logic;

import java.time.DayOfWeek;

public final class RoomAssignments {
    private RoomAssignments() {
    }

    public static String forSlot(int dayOfWeek, int period) {
        if (dayOfWeek == DayOfWeek.MONDAY.getValue()) {
            if (period == 1 || period == 2) return "226";
            if (period == 3) return "222";
            if (period == 4) return "108";
            if (period == 5 || period == 6) return "333";
            if (period == 7) return "107";
        } else if (dayOfWeek == DayOfWeek.TUESDAY.getValue()) {
            if (period == 1 || period == 2) return "330";
            if (period == 3 || period == 4) return "С.з. 2";
            if (period == 5 || period == 6) return "107";
            if (period == 7) return "108";
        } else if (dayOfWeek == DayOfWeek.WEDNESDAY.getValue()) {
            if (period == 1) return "333";
            if (period == 2) return "221";
            if (period == 3) return "220";
            if (period == 4) return "210";
            if (period == 5 || period == 6) return "222";
        } else if (dayOfWeek == DayOfWeek.THURSDAY.getValue()) {
            if (period == 1 || period == 2) return "336";
            if (period == 3 || period == 4) return "220";
            if (period == 5) return "108";
            if (period == 6) return "224";
        } else if (dayOfWeek == DayOfWeek.FRIDAY.getValue()) {
            if (period == 1) return "21";
            if (period == 2) return "214";
            if (period == 3 || period == 4) return "108";
            if (period == 5 || period == 6) return "107";
        } else if (dayOfWeek == DayOfWeek.SATURDAY.getValue()
                && (period == 1 || period == 2)) {
            return "330";
        }
        return null;
    }
}
