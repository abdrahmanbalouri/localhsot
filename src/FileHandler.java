package src;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class FileHandler implements HttpHandler {
    private final ErrorResponses errors;

    public FileHandler(ErrorResponses errors) {
        this.errors = errors;
    }

    @Override
    public HttpResponse handle(HttpRequest request, ConfigLoader.VHostConfig vhost, ConfigLoader.RouteConfig route) throws Exception {
        String method = request.getMethod();
        Path rootPath = Paths.get(vhost.root).toAbsolutePath().normalize();
        String relPath = request.getPath().substring(route.path.length());
        if (relPath.startsWith("/")) relPath = relPath.substring(1);
        Path targetPath = rootPath.resolve(relPath).normalize();

        if (!targetPath.startsWith(rootPath)) {
            return errors.build(403, vhost);
        }

        File file = targetPath.toFile();

        switch (method) {
            case "GET":
                return handleGet(file, targetPath, vhost, route, request.getPath());
            case "POST":
                return handlePost(targetPath, request, vhost);
            case "DELETE":
                return handleDelete(file, vhost);
            default:
                return errors.build(405, vhost);
        }
    }

    private HttpResponse handleGet(File file, Path targetPath, ConfigLoader.VHostConfig vhost,
                                    ConfigLoader.RouteConfig route, String requestPath) throws Exception {
        if (file.isDirectory()) {
            Path indexFile = findIndexFile(targetPath, route, vhost);
            if (indexFile != null) {
                return serveFile(indexFile);
            }
            if (vhost.allowDirectoryListing) {
                return listDirectory(file, requestPath);
            }
            return errors.build(403, vhost);
        }
        if (!file.exists()) {
            return errors.build(404, vhost);
        }
        return serveFile(targetPath);
    }

    private HttpResponse handlePost(Path targetPath, HttpRequest request, ConfigLoader.VHostConfig vhost) throws Exception {
        Path parent = targetPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(targetPath, request.getBody() != null ? request.getBody() : new byte[0]);
        HttpResponse res = new HttpResponse();
        res.setStatus(201);
        res.setBody("File uploaded: " + targetPath.getFileName());
        return res;
    }

    private HttpResponse handleDelete(File file, ConfigLoader.VHostConfig vhost) {
        if (!file.exists()) {
            return errors.build(404, vhost);
        }
        if (!file.delete()) {
            return errors.build(500, vhost);
        }
        HttpResponse res = new HttpResponse();
        res.setStatus(204);
        return res;
    }

    private Path findIndexFile(Path dir, ConfigLoader.RouteConfig route, ConfigLoader.VHostConfig vhost) {
        List<String> names = route.indexFiles.isEmpty() ? vhost.indexFiles : route.indexFiles;
        for (String name : names) {
            Path candidate = dir.resolve(name).normalize();
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private HttpResponse serveFile(Path path) throws Exception {
        HttpResponse res = new HttpResponse();
        res.setBody(Files.readAllBytes(path));
        res.addHeader("Content-Type", contentType(path.getFileName().toString()));
        return res;
    }

    private String contentType(String name) {
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private HttpResponse listDirectory(File dir, String path) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body><h1>Index of ").append(path).append("</h1><ul>");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                String sep = path.endsWith("/") ? "" : "/";
                sb.append("<li><a href=\"").append(path).append(sep).append(name).append("\">")
                  .append(name).append(f.isDirectory() ? "/" : "").append("</a></li>");
            }
        }
        sb.append("</ul></body></html>");
        HttpResponse res = new HttpResponse();
        res.setBody(sb.toString());
        res.addHeader("Content-Type", "text/html");
        return res;
    }
}
