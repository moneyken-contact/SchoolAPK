package by.myschedule.app.model;

public enum HomeworkStatus {
    NOT_DONE,
    PARTIAL,
    DONE;

    public HomeworkStatus next() {
        if (this == NOT_DONE) return PARTIAL;
        if (this == PARTIAL) return DONE;
        return NOT_DONE;
    }
}
