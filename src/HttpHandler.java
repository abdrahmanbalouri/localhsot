package src;

public interface HttpHandler {
    HttpResponse handle(HttpRequest request, ConfigLoader.VHostConfig vhost, ConfigLoader.RouteConfig route) throws Exception;
}
