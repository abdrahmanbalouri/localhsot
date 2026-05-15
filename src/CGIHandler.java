import java.io.*;
import java.nio.file.*;
import java.util.*;

public class CGIHandler {

    public static Process start(String scriptPath, String method,
            Map<String, String> headers, Path bodyFile, long bodyLength, String queryString) throws IOException {

        File scriptFile = new File(scriptPath);
        if (!scriptFile.exists() || !scriptFile.isFile()) {
            throw new FileNotFoundException("CGI script not found: " + scriptPath);
        }
        ProcessBuilder pb = new ProcessBuilder("python3", scriptPath);
        Map<String, String> env = pb.environment();
        env.put("REQUEST_METHOD", method);
        env.put("PATH_INFO", scriptPath);
        env.put("SCRIPT_FILENAME", scriptPath);
        env.put("QUERY_STRING", queryString != null ? queryString : "");
        env.put("CONTENT_TYPE", headers.getOrDefault("content-type", ""));
        env.put("CONTENT_LENGTH", String.valueOf(bodyLength));
        env.put("SERVER_PROTOCOL", "HTTP/1.1");
        env.put("GATEWAY_INTERFACE", "CGI/1.1");

        if (bodyFile != null) pb.redirectInput(bodyFile.toFile());
        else pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process p = pb.start();
        if (bodyFile == null) {
            p.getOutputStream().close();
        }
        return p;
    }
}
