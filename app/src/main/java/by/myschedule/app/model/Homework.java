package by.myschedule.app.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;

public final class Homework {
    public final String id;
    public String subject;
    public String description;
    public LocalDate dueDate;
    public HomeworkStatus status;
    public final long createdAt;

    public Homework(String id, String subject, String description, LocalDate dueDate,
                    HomeworkStatus status, long createdAt) {
        this.id = id;
        this.subject = subject;
        this.description = description;
        this.dueDate = dueDate;
        this.status = status;
        this.createdAt = createdAt;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("subject", subject)
                .put("description", description)
                .put("dueDate", dueDate.toString())
                .put("status", status.name())
                .put("createdAt", createdAt);
    }

    public static Homework fromJson(JSONObject json) throws JSONException {
        HomeworkStatus parsedStatus;
        try {
            parsedStatus = HomeworkStatus.valueOf(json.optString("status", HomeworkStatus.NOT_DONE.name()));
        } catch (IllegalArgumentException error) {
            parsedStatus = HomeworkStatus.NOT_DONE;
        }
        return new Homework(
                json.getString("id"),
                json.optString("subject", ""),
                json.optString("description", ""),
                LocalDate.parse(json.getString("dueDate")),
                parsedStatus,
                json.optLong("createdAt", System.currentTimeMillis())
        );
    }
}
