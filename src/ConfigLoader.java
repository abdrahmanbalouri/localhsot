import java.util.*;
import java.nio.file.*;

public class ConfigLoader {
    public static Map<String, Object> parse(String path) throws Exception {
        String content = Files.readString(Paths.get(path));
        return (Map<String, Object>) new Parser(content.trim()).parseValue();
    }

    static class Parser {
        String s; int i;

        Parser(String s) { this.s = s; }

        Object parseValue() {
            skipWS();
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') { boolean v = s.startsWith("true", i); i += v ? 4 : 5; return v; }
            if (c == 'n') { i += 4; return null; }
            return parseNumber();
        }

        Map<String, Object> parseObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                String k = (String) parseValue();
                skipWS(); i++;
                skipWS(); m.put(k, parseValue());
                skipWS();
                if (s.charAt(i) == '}') { i++; return m; }
                i++;
            }
        }

        List<Object> parseArray() {
            List<Object> a = new ArrayList<>();
            i++;
            if (s.charAt(i) == ']') { i++; return a; }
            while (true) {
                a.add(parseValue());
                skipWS();
                if (s.charAt(i) == ']') { i++; return a; }
                i++;
            }
        }

        String parseString() {
            i++;
            int start = i;
            while (s.charAt(i) != '"') { if (s.charAt(i) == '\\') i++; i++; }
            String v = s.substring(start, i);
            i++;
            return v;
        }

        Number parseNumber() {
            int start = i;
            if (s.charAt(i) == '-') i++;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            String n = s.substring(start, i);
            return n.contains(".") ? Double.parseDouble(n) : (long) Long.parseLong(n);
        }

        void skipWS() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    }
}
