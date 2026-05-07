package src;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class ConfigLoader {
    public static class RouteConfig {
        public String path;
        public String handler;
        public String cgiExtension;
        public String redirectTo;
        public List<String> methods = new ArrayList<>();
        public List<String> indexFiles = new ArrayList<>();
    }

    public static class VHostConfig {
        public String domain;
        public String root;
        public boolean allowDirectoryListing;
        public Map<Integer, String> errorPages = new HashMap<>();
        public List<String> indexFiles = new ArrayList<>();
        public List<RouteConfig> routes = new ArrayList<>();
    }

    public static class ServerConfig {
        public List<Integer> ports = new ArrayList<>();
        public long keepAliveTimeoutMs = 30000;
        public long sessionTimeoutSec = 1800;
        public int bufferSize = 8192;
        public int maxHeaderSize = 8192;
        public long maxBodySize = 1048576;
        public Map<Integer, String> errorPages = new HashMap<>();
    }

    public static class AppConfig {
        public ServerConfig server = new ServerConfig();
        public List<VHostConfig> vhosts = new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public static AppConfig loadConfig(String path) throws IOException {
        Path configPath = Paths.get(path);
        String content = new String(Files.readAllBytes(configPath));
        try {
            Object parsed = parseJson(content);
            if (parsed instanceof Map) {
                return mapToAppConfig((Map<String, Object>) parsed);
            }
        } catch (Exception e) {
            System.err.println("Failed to parse " + configPath.toAbsolutePath() + " : " + e.getMessage());
        }
        return new AppConfig();
    }

    @SuppressWarnings("unchecked")
    private static AppConfig mapToAppConfig(Map<String, Object> map) {
        AppConfig config = new AppConfig();
        if (map.containsKey("server")) {
            Map<String, Object> sMap = (Map<String, Object>) map.get("server");
            if (sMap.containsKey("ports")) {
                List<Object> pList = (List<Object>) sMap.get("ports");
                for (Object p : pList) {
                    config.server.ports.add(((Number) p).intValue());
                }
            }
            if (sMap.containsKey("keepAliveTimeoutMs"))
                config.server.keepAliveTimeoutMs = ((Number) sMap.get("keepAliveTimeoutMs")).longValue();
            if (sMap.containsKey("sessionTimeoutSec"))
                config.server.sessionTimeoutSec = ((Number) sMap.get("sessionTimeoutSec")).longValue();
            if (sMap.containsKey("bufferSize"))
                config.server.bufferSize = ((Number) sMap.get("bufferSize")).intValue();
            if (sMap.containsKey("maxHeaderSize"))
                config.server.maxHeaderSize = ((Number) sMap.get("maxHeaderSize")).intValue();
            if (sMap.containsKey("maxBodySize"))
                config.server.maxBodySize = ((Number) sMap.get("maxBodySize")).longValue();
            readErrorPages(sMap.get("errorPages"), config.server.errorPages);
        }

        if (map.containsKey("vhosts")) {
            List<Object> vList = (List<Object>) map.get("vhosts");
            for (Object vObj : vList) {
                Map<String, Object> vMap = (Map<String, Object>) vObj;
                VHostConfig vhost = new VHostConfig();
                if (vMap.containsKey("domain"))
                    vhost.domain = (String) vMap.get("domain");
                if (vMap.containsKey("root"))
                    vhost.root = (String) vMap.get("root");
                if (vMap.containsKey("allowDirectoryListing"))
                    vhost.allowDirectoryListing = (Boolean) vMap.get("allowDirectoryListing");
                readStringList(vMap.get("indexFiles"), vhost.indexFiles, false);
                readErrorPages(vMap.get("errorPages"), vhost.errorPages);

                if (vMap.containsKey("routes")) {
                    List<Object> rList = (List<Object>) vMap.get("routes");
                    for (Object rObj : rList) {
                        Map<String, Object> rMap = (Map<String, Object>) rObj;
                        RouteConfig rc = new RouteConfig();
                        if (rMap.containsKey("path"))
                            rc.path = (String) rMap.get("path");
                        if (rMap.containsKey("handler"))
                            rc.handler = (String) rMap.get("handler");
                        if (rMap.containsKey("cgiExtension"))
                            rc.cgiExtension = (String) rMap.get("cgiExtension");
                        if (rMap.containsKey("redirectTo"))
                            rc.redirectTo = (String) rMap.get("redirectTo");
                        readStringList(rMap.get("methods"), rc.methods, true);
                        readStringList(rMap.get("indexFiles"), rc.indexFiles, false);
                        vhost.routes.add(rc);
                    }
                }
                config.vhosts.add(vhost);
            }
        }
        return config;
    }

    private static void readStringList(Object value, List<String> output, boolean uppercase) {
        if (!(value instanceof List)) return;
        for (Object item : (List<?>) value) {
            if (item != null) {
                String text = item.toString();
                output.add(uppercase ? text.toUpperCase() : text);
            }
        }
    }

    private static void readErrorPages(Object value, Map<Integer, String> output) {
        if (!(value instanceof Map)) return;
        Map<?, ?> pages = (Map<?, ?>) value;
        for (Map.Entry<?, ?> entry : pages.entrySet()) {
            try {
                output.put(Integer.parseInt(entry.getKey().toString()), entry.getValue().toString());
            } catch (NumberFormatException ignored) {
                System.err.println("Ignoring invalid error page status: " + entry.getKey());
            }
        }
    }

    // Mini JSON Parser
    private static Object parseJson(String json) {
        return new JsonParser(json).parseValue();
    }

    private static class JsonParser {
        private String json;
        private int pos = 0;

        public JsonParser(String json) {
            this.json = json;
        }

        public Object parseValue() {
            skipWhitespace();
            if (pos >= json.length())
                return null;
            char c = json.charAt(pos);
            if (c == '{')
                return parseObject();
            if (c == '[')
                return parseArray();
            if (c == '"')
                return parseString();
            if (c == 't' || c == 'f')
                return parseBoolean();
            if (c == 'n')
                return parseNull();
            return parseNumber();
        }

        private void skipWhitespace() {
            while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
                pos++;
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new HashMap<>();
            pos++; // skip '{'
            skipWhitespace();
            if (pos < json.length() && json.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (json.charAt(pos) != ':')
                    throw new RuntimeException("Expected ':' at " + pos);
                pos++; // skip ':'
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (pos < json.length() && json.charAt(pos) == '}') {
                    pos++;
                    break;
                }
                if (pos >= json.length() || json.charAt(pos) != ',')
                    throw new RuntimeException("Expected ',' or '}' at " + pos);
                pos++; // skip ','
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // skip '['
            skipWhitespace();
            if (pos < json.length() && json.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                Object value = parseValue();
                list.add(value);
                skipWhitespace();
                if (pos < json.length() && json.charAt(pos) == ']') {
                    pos++;
                    break;
                }
                if (pos >= json.length() || json.charAt(pos) != ',')
                    throw new RuntimeException("Expected ',' or ']' at " + pos);
                pos++; // skip ','
            }
            return list;
        }

        private String parseString() {
            if (pos >= json.length() || json.charAt(pos) != '"')
                throw new RuntimeException("invalid json format '\"' at " + pos);
            pos++; // skip '"'
            int start = pos;
            while (pos < json.length() && json.charAt(pos) != '"') {
                if (json.charAt(pos) == '\\')
                    pos++; // skip escaped char
                pos++;
            }
            if (pos >= json.length())
                throw new RuntimeException("Unterminated string at " + start);
            String str = json.substring(start, pos);
            pos++; // skip '"'
            return str;
        }

        private Boolean parseBoolean() {
            if (json.startsWith("true", pos)) {
                pos += 4;
                return true;
            } else if (json.startsWith("false", pos)) {
                pos += 5;
                return false;
            }
            throw new RuntimeException("Invalid boolean at " + pos);
        }

        private Object parseNull() {
            if (json.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new RuntimeException("Invalid null at " + pos);
        }

        private Number parseNumber() {
            int start = pos;
            while (pos < json.length()
                    && (Character.isDigit(json.charAt(pos)) || json.charAt(pos) == '-' || json.charAt(pos) == '.')) {
                pos++;
            }
            String numStr = json.substring(start, pos);
            if (numStr.isEmpty())
                throw new RuntimeException("Expected value at " + pos);
            if (numStr.contains(".")) {
                return Double.parseDouble(numStr);
            } else {
                return Long.parseLong(numStr);
            }
        }
    }
}
