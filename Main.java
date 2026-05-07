package src;

import java.io.IOException;

public class Main {
    public static void main(String[] args) {
        String configPath = "config.json";
        if (args.length > 0 && !args[0].trim().isEmpty()) {
            configPath = args[0];
        }

        try {
            ConfigLoader.AppConfig config = ConfigLoader.loadConfig(configPath);
            Server server = new Server(config);
            server.start();
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
