package by.myschedule.app.email;

import java.util.concurrent.atomic.AtomicBoolean;

public final class EmailSyncGate {
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private EmailSyncGate() {
    }

    public static boolean tryStart() {
        return RUNNING.compareAndSet(false, true);
    }

    public static void finish() {
        RUNNING.set(false);
    }
}
