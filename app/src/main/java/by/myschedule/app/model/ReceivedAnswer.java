package by.myschedule.app.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;

public final class ReceivedAnswer {
    public final String id;
    public final String messageKey;
    public String subject;
    public String answerText;
    public String homeworkText;
    public LocalDate lessonDate;
    public final long receivedAt;

    public ReceivedAnswer(String id, String messageKey, String subject, String answerText,
                          String homeworkText, LocalDate lessonDate, long receivedAt) {
        this.id = id;
        this.messageKey = messageKey;
        this.subject = subject;
        this.answerText = answerText;
        this.homeworkText = homeworkText;
        this.lessonDate = lessonDate;
        this.receivedAt = receivedAt;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("messageKey", messageKey)
                .put("subject", subject)
                .put("answerText", answerText)
                .put("homeworkText", homeworkText)
                .put("lessonDate", lessonDate.toString())
                .put("receivedAt", receivedAt);
    }

    public static ReceivedAnswer fromJson(JSONObject json) throws JSONException {
        return new ReceivedAnswer(
                json.getString("id"),
                json.optString("messageKey", json.getString("id")),
                json.optString("subject", ""),
                json.optString("answerText", ""),
                json.optString("homeworkText", ""),
                LocalDate.parse(json.getString("lessonDate")),
                json.optLong("receivedAt", System.currentTimeMillis())
        );
    }
}
