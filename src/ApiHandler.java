package src;

public class ApiHandler implements HttpHandler {
    @Override
    public HttpResponse handle(HttpRequest request, ConfigLoader.VHostConfig vhost, ConfigLoader.RouteConfig route) {
        HttpResponse res = new HttpResponse();
        res.setStatus(200);
        res.addHeader("Content-Type", "application/json");
        res.setBody("{\"status\":\"ok\",\"message\":\"Hello from API\"}".getBytes());
        return res;
    }
}
