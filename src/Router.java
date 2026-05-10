import java.util.*;

public class Router {
    private List<Server.VirtualServer> servers;

    public Router(List<Server.VirtualServer> servers) {
        this.servers = servers;
    }

    public Server.Route match(Server.VirtualServer vs, String path) {
        Server.Route best = null;
        int bestLen = -1;
        for (Server.Route r : vs.routes) {
            if (path.startsWith(r.path) && r.path.length() > bestLen) {
                best = r;
                bestLen = r.path.length();
            }
        }
        return best;
    }
}
