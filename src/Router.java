import java.util.*;

public class Router {
    private List<Server.VirtualServer> servers;

    public Router(List<Server.VirtualServer> servers) {
        this.servers = servers;
    }

    public Server.VirtualServer matchServer(String host) {
        if (host == null || servers.isEmpty()) return servers.get(0);
        String h = host.contains(":") ? host.split(":")[0] : host;
        return servers.stream()
            .filter(vs -> vs.serverName.equals(h))
            .findFirst()
            .orElse(servers.get(0));
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
