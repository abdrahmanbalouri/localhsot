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

        String page = findErrorPage(status, vhost);
        if (page != null && loadPage(response, page, vhost)) {
            return response;
        }

        response.setBody(defaultMessage(status));
        response.addHeader("Content-Type", "text/html");
        return response;
    }

    private String findErrorPage(int status, ConfigLoader.VHostConfig vhost) {
        if (vhost != null && vhost.errorPages.containsKey(status)) {
            return vhost.errorPages.get(status);
        }
        return config.server.errorPages.get(status);
    }

    private boolean loadPage(HttpResponse response, String errorPage, ConfigLoader.VHostConfig vhost) {
        try {
            Path path = Paths.get(errorPage);
            if (!path.isAbsolute() && vhost != null && vhost.root != null) {
                path = Paths.get(vhost.root).resolve(errorPage).normalize();
            }
            response.setBody(Files.readAllBytes(path));
            response.addHeader("Content-Type", "text/html");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String defaultMessage(int status) {
        String[] messages = {
            "400", "Bad Request",
            "403", "Forbidden",
            "404", "Not Found",
            "405", "Method Not Allowed",
            "413", "Payload Too Large",
            "500", "Internal Server Error"
        };
        for (int i = 0; i < messages.length; i += 2) {
            if (Integer.parseInt(messages[i]) == status) {
                return "<html><body><h1>" + status + " " + messages[i + 1] + "</h1></body></html>";
            }
        }
        return "<html><body><h1>" + status + " Error</h1></body></html>";
    }
}
