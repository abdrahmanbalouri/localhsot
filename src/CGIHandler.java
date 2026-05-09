import java.io.*;
import java.util.*;

public class CGIHandler {
    private static final int TIMEOUT = 10;

    public static byte[] execute(String scriptPath, String method,
            Map<String, String> headers, byte[] body, String queryString) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("python3", scriptPath);
        Map<String, String> env = pb.environment();
        env.put("REQUEST_METHOD", method);
        env.put("PATH_INFO", scriptPath);
        env.put("SCRIPT_FILENAME", scriptPath);
        env.put("QUERY_STRING", queryString != null ? queryString : "");
        env.put("CONTENT_TYPE", headers.getOrDefault("content-type", ""));
        env.put("CONTENT_LENGTH", String.valueOf(body != null ? body.length : 0));
        env.put("SERVER_PROTOCOL", "HTTP/1.1");
        env.put("GATEWAY_INTERFACE", "CGI/1.1");

        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process p = pb.start();
        if (body != null && body.length > 0) p.getOutputStream().write(body);
        p.getOutputStream().close();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        InputStream is = p.getInputStream();
        long deadline = System.currentTimeMillis() + TIMEOUT * 1000;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (is.available() > 0) {
                    int n = is.read(buf);
                    if (n == -1) break;
                    out.write(buf, 0, n);
                } else {
                    try { p.exitValue(); break; } catch (IllegalThreadStateException e) {}
                    Thread.sleep(5);
                }
            }
        } catch (Exception e) {}
        p.destroyForcibly();
        return out.toByteArray();
    }
}
