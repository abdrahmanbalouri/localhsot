import java.util.*;

public class Main {
    public static void main(String[] args) throws Exception {
        String configFile = args.length > 0 ? args[0] : "config.json";
        Map<String, Object> config = ConfigLoader.parse(configFile);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> servers = (List<Map<String, Object>>) config.get("servers");
        if (servers == null || servers.isEmpty()) {
            System.err.println("No servers configured");
            System.exit(1);
        }
        new Server(servers).start();
    }
}
