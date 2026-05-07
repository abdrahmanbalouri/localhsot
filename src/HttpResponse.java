package src;

import java.util.HashMap;
import java.util.Map;

public class HttpResponse {
    private int status = 200;
    private String statusText = "OK";
    private Map<String, String> headers = new HashMap<>();
    private byte[] body;

    private static final Map<Integer, String> STATUS_MESSAGES = new HashMap<>();
    static {
        STATUS_MESSAGES.put(200, "OK");
        STATUS_MESSAGES.put(201, "Created");
        STATUS_MESSAGES.put(204, "No Content");
        STATUS_MESSAGES.put(301, "Moved Permanently");
        STATUS_MESSAGES.put(400, "Bad Request");
        STATUS_MESSAGES.put(403, "Forbidden");
        STATUS_MESSAGES.put(404, "Not Found");
        STATUS_MESSAGES.put(405, "Method Not Allowed");
        STATUS_MESSAGES.put(413, "Payload Too Large");
        STATUS_MESSAGES.put(500, "Internal Server Error");
    }

    public HttpResponse() {
        headers.put("Server", "JavaCustomServer/1.0");
        headers.put("Connection", "close");
    }

    public void setStatus(int code) {
        this.status = code;
        this.statusText = STATUS_MESSAGES.getOrDefault(code, "Unknown");
    }

    public void addHeader(String key, String value) {
        headers.put(key, value);
    }

    public void setBody(byte[] body) {
        this.body = body;
        addHeader("Content-Length", String.valueOf(body.length));
    }

    public void setBody(String body) {
        setBody(body.getBytes());
    }

    public byte[] getBytes() {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(" ").append(statusText).append("\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            sb.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        
        byte[] headerBytes = sb.toString().getBytes();
        if (body == null) return headerBytes;
        
        byte[] full = new byte[headerBytes.length + body.length];
        System.arraycopy(headerBytes, 0, full, 0, headerBytes.length);
        System.arraycopy(body, 0, full, headerBytes.length, body.length);
        return full;
    }
}
