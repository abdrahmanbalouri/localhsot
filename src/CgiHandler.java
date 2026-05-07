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
        Path script = scriptPath(request, vhost);
        Path root = Paths.get(vhost.root).toAbsolutePath().normalize();
        if (!script.startsWith(root)) {
            return error(403, "Forbidden");
        }
        if (!Files.isRegularFile(script)) {
            return error(404, "CGI script not found");
        }
        if (route.cgiExtension != null && !script.toString().endsWith(route.cgiExtension)) {
            return error(403, "CGI extension not allowed");
        }

        Path output = Files.createTempFile("cgi-out-", ".tmp");
        ProcessBuilder builder = new ProcessBuilder(commandFor(script.toString()));
        addEnvironment(builder.environment(), request);
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

    private Path scriptPath(HttpRequest request, ConfigLoader.VHostConfig vhost) {
        String path = request.getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return Paths.get(vhost.root).toAbsolutePath().normalize().resolve(path).normalize();
    }

    private void addEnvironment(Map<String, String> env, HttpRequest request) {
        env.put("REQUEST_METHOD", request.getMethod());
        env.put("PATH_INFO", request.getPath());
        env.put("QUERY_STRING", request.getQueryParams().toString());
        env.put("CONTENT_LENGTH", request.getHeaders().getOrDefault("content-length", "0"));
        env.put("CONTENT_TYPE", request.getHeaders().getOrDefault("content-type", ""));
    }

    private List<String> commandFor(String scriptPath) {
        List<String> command = new ArrayList<>();
        if (scriptPath.endsWith(".py")) command.add("python3");
        else if (scriptPath.endsWith(".sh")) command.add("bash");
        else if (scriptPath.endsWith(".js")) command.add("node");
        command.add(scriptPath);
        return command;
    }

    private HttpResponse fromCgiOutput(byte[] output) {
        HttpResponse response = new HttpResponse();
        String text = new String(output);
        int headerEnd = text.indexOf("\r\n\r\n");
        int separatorSize = 4;
        if (headerEnd == -1) {
            headerEnd = text.indexOf("\n\n");
            separatorSize = 2;
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
        response.setBody(text.substring(headerEnd + separatorSize).getBytes());
        return response;
    }

    private HttpResponse error(int status, String message) {
        HttpResponse response = new HttpResponse();
        response.setStatus(status);
        response.setBody(message);
        return response;
    }
}
