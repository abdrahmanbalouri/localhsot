import java.util.*;
import java.util.concurrent.*;

public class SessionManager {
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private static final long MAX_AGE = 3600000;

    public String createSession() {
        String id = Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong());
        sessions.put(id, new Session(id));
        return id;
    }

    public Session get(String id) {
        Session s = sessions.get(id);
        if (s != null && System.currentTimeMillis() - s.createdAt > MAX_AGE) {
            sessions.remove(id);
            return null;
        }
        return s;
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now - e.getValue().createdAt > MAX_AGE);
    }

    public static class Session {
        public final String id;
        public final long createdAt = System.currentTimeMillis();
        public final Map<String, String> data = new HashMap<>();
        Session(String id) { this.id = id; }
    }
}
