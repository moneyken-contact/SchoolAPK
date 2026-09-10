package by.myschedule.app.model;

import org.json.JSONException;
import org.json.JSONObject;

public final class LessonBlock {
    public final String id;
    public final int dayOfWeek;
    public final int startPeriod;
    public final int endPeriod;
    public String subject;
    public String room;

    public LessonBlock(String id, int dayOfWeek, int startPeriod, int endPeriod, String subject, String room) {
        this.id = id;
        this.dayOfWeek = dayOfWeek;
        this.startPeriod = startPeriod;
        this.endPeriod = endPeriod;
        this.subject = subject;
        this.room = room;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("dayOfWeek", dayOfWeek)
                .put("startPeriod", startPeriod)
                .put("endPeriod", endPeriod)
                .put("subject", subject)
                .put("room", room);
    }

    public static LessonBlock fromJson(JSONObject json) throws JSONException {
        return new LessonBlock(
                json.getString("id"),
                json.getInt("dayOfWeek"),
                json.getInt("startPeriod"),
                json.getInt("endPeriod"),
                json.optString("subject", ""),
                json.optString("room", "")
        );
    }
}
