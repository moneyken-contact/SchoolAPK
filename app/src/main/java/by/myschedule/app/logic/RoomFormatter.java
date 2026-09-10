package by.myschedule.app.logic;

import java.util.Locale;

public final class RoomFormatter {
    private RoomFormatter() {
    }

    public static String compact(String value) {
        String room = value == null ? "" : value.trim();
        if (room.isEmpty()) return "каб. —";
        if (isSportsHall(room)) return room;
        return "каб. " + room;
    }

    public static String editor(String value) {
        String room = value == null ? "" : value.trim();
        if (room.isEmpty()) return "Кабинет не указан";
        if (isSportsHall(room)) return room;
        return "Кабинет " + room;
    }

    private static boolean isSportsHall(String room) {
        String normalized = room.toLowerCase(Locale.ROOT);
        return normalized.startsWith("с.з.")
                || normalized.startsWith("с/з")
                || normalized.startsWith("спортзал")
                || normalized.startsWith("спортивный зал");
    }
}
