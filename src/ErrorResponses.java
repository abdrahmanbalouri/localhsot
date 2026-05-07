package src;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ErrorResponses {
    private final ConfigLoader.AppConfig config;

    public ErrorResponses(ConfigLoader.AppConfig config) {
        this.config = config;
    }

    public HttpResponse build(int status) {
        return build(status, null);
    }

    public HttpResponse build(int status, ConfigLoader.VHostConfig vhost) {
        HttpResponse response = new HttpResponse();
        response.setStatus(status);

        String errorPage = errorPageFor(status, vhost);
        if (errorPage != null && loadErrorPage(response, errorPage, vhost)) {
            return response;
        }

        response.setBody("Error " + status);
        response.addHeader("Content-Type", "text/plain");
        return response;
    }

    private String errorPageFor(int status, ConfigLoader.VHostConfig vhost) {
        if (vhost != null && vhost.errorPages.containsKey(status)) {
            return vhost.errorPages.get(status);
        }
        return config.server.errorPages.get(status);
    }

    private boolean loadErrorPage(HttpResponse response, String errorPage, ConfigLoader.VHostConfig vhost) {
        try {
            Path path = Paths.get(errorPage).normalize();
            if (!path.isAbsolute() && vhost != null && vhost.root != null) {
                path = Paths.get(vhost.root).resolve(errorPage).normalize();
            }

            response.setBody(Files.readAllBytes(path));
            response.addHeader("Content-Type", "text/html");
            return true;
        } catch (IOException e) {
            System.err.println("Could not read configured error page: " + errorPage);
            return false;
        }
    }
}
