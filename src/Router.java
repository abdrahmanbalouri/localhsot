package src;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Router {
    private final ConfigLoader.AppConfig config;
    private final ErrorResponses errors;
    private final Map<String, HttpHandler> handlers = new HashMap<>();
    private final Map<String, Long> sessions = new HashMap<>();

    public Router(ConfigLoader.AppConfig config) {
        this.config = config;
        this.errors = new ErrorResponses(config);
        handlers.put("ApiHandler", new ApiHandler());
        handlers.put("FileHandler", new FileHandler(errors));
        handlers.put("CgiHandler", new CgiHandler());
    }

    public HttpResponse handle(HttpRequest request) {
        ConfigLoader.VHostConfig vhost = findVHost(request.getHeaders().get("host"));
        if (vhost == null) {
            return errorResponse(404);
        }

        ConfigLoader.RouteConfig route = findRoute(vhost, request.getPath());
        if (route == null) {
            return errorResponse(404, vhost);
        }

        if (!route.methods.isEmpty() && !route.methods.contains(request.getMethod())) {
            HttpResponse response = errorResponse(405, vhost);
            response.addHeader("Allow", String.join(", ", route.methods));
            return response;
        }

        if (route.redirectTo != null) {
            HttpResponse response = new HttpResponse();
            response.setStatus(301);
            response.addHeader("Location", route.redirectTo);
            response.setBody("Moved Permanently");
            return response;
        }

        HttpHandler handler = handlers.get(route.handler);
        if (handler == null) {
            return errorResponse(500, vhost);
        }

        try {
            Session session = session(request);
            HttpResponse response = handler.handle(request, vhost, route);
            if (session.isNew) {
                response.addHeader("Set-Cookie", "JSESSIONID=" + session.id + "; Path=/; HttpOnly; SameSite=Strict");
            }
            return response;
        } catch (Exception e) {
            e.printStackTrace();
            return errorResponse(500, vhost);
        }
    }

    public HttpResponse errorResponse(int status) {
        return errors.build(status);
    }

    private HttpResponse errorResponse(int status, ConfigLoader.VHostConfig vhost) {
        return errors.build(status, vhost);
    }

    private ConfigLoader.VHostConfig findVHost(String host) {
        if (config.vhosts.isEmpty()) {
            return null;
        }
        if (host == null) {
            return config.vhosts.get(0);
        }

        String hostName = host.split(":", 2)[0];
        for (ConfigLoader.VHostConfig vhost : config.vhosts) {
            if (hostName.equalsIgnoreCase(vhost.domain)) {
                return vhost;
            }
        }
        return config.vhosts.get(0);
    }

    private ConfigLoader.RouteConfig findRoute(ConfigLoader.VHostConfig vhost, String path) {
        ConfigLoader.RouteConfig best = null;
        for (ConfigLoader.RouteConfig route : vhost.routes) {
            if (path.startsWith(route.path) && (best == null || route.path.length() > best.path.length())) {
                best = route;
            }
        }
        return best;
    }

    private Session session(HttpRequest request) {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> now - entry.getValue() > config.server.sessionTimeoutSec * 1000);

        String id = sessionId(request.getHeaders().get("cookie"));
        if (id != null && sessions.containsKey(id)) {
            sessions.put(id, now);
            return new Session(id, false);
        }

        id = UUID.randomUUID().toString();
        sessions.put(id, now);
        return new Session(id, true);
    }

    private String sessionId(String cookieHeader) {
        if (cookieHeader == null) return null;
        for (String cookie : cookieHeader.split(";")) {
            cookie = cookie.trim();
            if (cookie.startsWith("JSESSIONID=")) {
                return cookie.substring("JSESSIONID=".length());
            }
        }
        return null;
    }

    private static class Session {
        private final String id;
        private final boolean isNew;

        private Session(String id, boolean isNew) {
            this.id = id;
            this.isNew = isNew;
        }
    }
}
