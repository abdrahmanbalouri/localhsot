package src;

import java.util.HashMap;
import java.util.Map;

public class HttpRequest {
    private String method;
    private String path;
    private String version;
    private Map<String, String> headers = new HashMap<>();
    private byte[] body;
    private Map<String, String> queryParams = new HashMap<>();

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method == null ? null : method.toUpperCase(); }

    public String getPath() { return path; }
    public void setPath(String path) {
        if (path.contains("../") || path.indexOf('\0') != -1) {
            throw new IllegalArgumentException("Invalid path sequence detected");
        }
        if (path.contains("?")) {
            String[] parts = path.split("\\?", 2);
            this.path = parts[0];
            parseQueryParams(parts[1]);
        } else {
            this.path = path;
        }
    }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public Map<String, String> getHeaders() { return headers; }
    public void addHeader(String key, String value) {
        headers.put(key.toLowerCase(), value.trim());
    }

    public byte[] getBody() { return body; }
    public void setBody(byte[] body) { this.body = body; }

    public Map<String, String> getQueryParams() { return queryParams; }

    private void parseQueryParams(String query) {
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                queryParams.put(kv[0], kv[1]);
            } else {
                queryParams.put(kv[0], "");
            }
        }
    }

    @Override
    public String toString() {
        return method + " " + path + " " + version;
    }
}
