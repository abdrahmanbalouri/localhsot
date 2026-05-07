package src;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

public class FileHandler implements HttpHandler {
    private final ErrorResponses errors;

    public FileHandler(ErrorResponses errors) {
        this.errors = errors;
    }

    @Override
    public HttpResponse handle(HttpRequest request, ConfigLoader.VHostConfig vhost, ConfigLoader.RouteConfig route) throws Exception {
        String relativePath = request.getPath().substring(route.path.length());
        if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
        Path rootPath = Paths.get(vhost.root).toAbsolutePath().normalize();
        Path targetPath = rootPath.resolve(relativePath).normalize();
        if (!targetPath.startsWith(rootPath)) {
            return errorResponse(403, vhost);
        }
        File file = targetPath.toFile();

        HttpResponse res = new HttpResponse();

        if (file.isDirectory()) {
            Path indexPath = findIndexFile(targetPath, route, vhost);
            if (indexPath != null) {
                res = serveFile(indexPath);
            } else if (vhost.allowDirectoryListing) {
                res = listDirectory(file, request.getPath());
            } else {
                res = errorResponse(403, vhost);
            }
        } else if (request.getMethod().equals("GET")) {
            if (!file.exists()) {
                res = errorResponse(404, vhost);
            } else {
                res = serveFile(file.toPath());
            }
        } else if (request.getMethod().equals("POST")) {
            // Restrict uploads to /upload or /uploads paths only
            String path = request.getPath();
            if (!path.startsWith("/upload")) {
                return errorResponse(403, vhost);
            }
            Path uploadDir = rootPath.resolve("uploads").normalize();
            // Generate UUID filename with original extension
            String filename = path.substring(path.lastIndexOf('/') + 1);
            String extension = filename.contains(".") ? filename.substring(filename.lastIndexOf('.')) : "";
            String uniqueFilename = UUID.randomUUID().toString() + extension;
            targetPath = uploadDir.resolve(uniqueFilename).normalize();
            Path parent = targetPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(targetPath, request.getBody() != null ? request.getBody() : new byte[0]);
            res.setStatus(201);
            res.setBody(("File uploaded successfully: " + uniqueFilename).getBytes());
        } else if (request.getMethod().equals("DELETE")) {
            if (!file.exists()) {
                res = errorResponse(404, vhost);
            } else if (file.delete()) {
                res.setStatus(204);
            } else {
                res = errorResponse(500, vhost);
            }
        } else {
            res = errorResponse(405, vhost);
        }

        return res;
    }

    private Path findIndexFile(Path directory, ConfigLoader.RouteConfig route, ConfigLoader.VHostConfig vhost) {
        List<String> indexFiles = route.indexFiles.isEmpty() ? vhost.indexFiles : route.indexFiles;
        for (String indexFile : indexFiles) {
            Path candidate = directory.resolve(indexFile).normalize();
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private HttpResponse serveFile(Path path) throws Exception {
        HttpResponse res = new HttpResponse();
        res.setStatus(200);
        res.setBody(Files.readAllBytes(path));
        res.addHeader("Content-Type", contentType(path.getFileName().toString()));
        return res;
    }

    private String contentType(String name) {
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private HttpResponse errorResponse(int status, ConfigLoader.VHostConfig vhost) {
        return errors.build(status, vhost);
    }

    private HttpResponse listDirectory(File dir, String path) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body><h1>Index of ").append(path).append("</h1><ul>");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                sb.append("<li><a href=\"").append(path).append(path.endsWith("/") ? "" : "/").append(f.getName()).append("\">")
                  .append(f.getName()).append(f.isDirectory() ? "/" : "").append("</a></li>");
            }
        }
        sb.append("</ul></body></html>");
        HttpResponse res = new HttpResponse();
        res.setStatus(200);
        res.setBody(sb.toString().getBytes());
        res.addHeader("Content-Type", "text/html");
        return res;
    }
}
