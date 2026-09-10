package by.myschedule.app.model;

public final class EmailSettings {
    public final boolean enabled;
    public final String host;
    public final int port;
    public final String username;

    public EmailSettings(boolean enabled, String host, int port, String username) {
        this.enabled = enabled;
        this.host = host == null ? "" : host.trim();
        this.port = port;
        this.username = username == null ? "" : username.trim();
    }

    public boolean isReady(String password) {
        return enabled && !host.isEmpty() && port > 0 && !username.isEmpty()
                && password != null && !password.isEmpty();
    }
}
