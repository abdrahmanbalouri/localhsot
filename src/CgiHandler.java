package src;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CgiHandler implements HttpHandler {
    @Override
    public HttpResponse handle(HttpRequest request, ConfigLoader.VHostConfig vhost, ConfigLoader.RouteConfig route) throws Exception {
        Path root = Paths.get(vhost.root).toAbsolutePath().normalize();
        Path script = root.resolve(request.getPath().substring(1)).normalize();

        if (!script.startsWith(root)) {
            return error(403, "Forbidden");
        }
        if (!Files.isRegularFile(script)) {
            return error(404, "CGI script not found");
        }
        if (route.cgiExtension != null && !script.toString().endsWith(route.cgiExtension)) {
            return error(403, "CGI extension not allowed");
        }

        Path output = Files.createTempFile("cgi-", ".tmp");
        ProcessBuilder builder = new ProcessBuilder(commandFor(script.toString()));
        addEnvironment(builder.environment(), request, root);
        builder.redirectOutput(output.toFile());
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process process = builder.start();
        if (request.getBody() != null) {
            process.getOutputStream().write(request.getBody());
        }
        process.getOutputStream().close();
        process.waitFor();

        byte[] bytes = Files.readAllBytes(output);
        Files.deleteIfExists(output);
        return fromCgiOutput(bytes);
    }

    private void addEnvironment(Map<String, String> env, HttpRequest request, Path root) {
        env.put("REQUEST_METHOD", request.getMethod());
        env.put("PATH_INFO", request.getPath());
        env.put("SCRIPT_FILENAME", root.resolve(request.getPath().substring(1)).toString());
        env.put("QUERY_STRING", buildQueryString(request));
        env.put("CONTENT_LENGTH", request.getHeaders().getOrDefault("content-length", "0"));
        env.put("CONTENT_TYPE", request.getHeaders().getOrDefault("content-type", ""));
    }

    private String buildQueryString(HttpRequest request) {
        Map<String, String> params = request.getQueryParams();
        if (params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    private List<String> commandFor(String scriptPath) {
        List<String> command = new ArrayList<>();
        if (scriptPath.endsWith(".py")) command.add("python3");
        else if (scriptPath.endsWith(".sh")) command.add("bash");
        command.add(scriptPath);
        return command;
    }

    private HttpResponse fromCgiOutput(byte[] output) {
        HttpResponse response = new HttpResponse();
        String text = new String(output);

        int headerEnd = text.indexOf("\r\n\r\n");
        int sepSize = 4;
        if (headerEnd == -1) {
            headerEnd = text.indexOf("\n\n");
            sepSize = 2;
        }

        if (headerEnd == -1) {
            response.setBody(output);
            return response;
        }

        for (String line : text.substring(0, headerEnd).split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                response.addHeader(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        response.setBody(text.substring(headerEnd + sepSize).getBytes());
        return response;
    }

    private HttpResponse error(int status, String message) {
        HttpResponse response = new HttpResponse();
        response.setStatus(status);
        response.setBody(message);
        return response;
    }
}
