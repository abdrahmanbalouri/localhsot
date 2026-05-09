import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class Server {
    public static class Route {
        String path;
        Set<String> methods;
        String root, defaultFile, redirect;
        boolean directoryListing;
        Set<String> cgiExtensions;
        int clientBodyLimit = 1_048_576;
    }

    public static class VirtualServer {
        String host = "127.0.0.1", serverName = "";
        int[] ports;
        Map<Integer, String> errorPages = new HashMap<>();
        int clientBodyLimit = 1_048_576;
        List<Route> routes = new ArrayList<>();
    }

    static class Connection {
        ByteBuffer buf = ByteBuffer.allocate(8192);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        VirtualServer server; Route route;
        String method, path, queryString, version;
        Map<String, String> headers = new LinkedHashMap<>();
        byte[] body;
        int statusCode = 200;
        String statusMessage = "OK";
        Map<String, String> resHeaders = new LinkedHashMap<>();
        byte[] resBody;
        long lastActive = System.currentTimeMillis();
        boolean parseError;
    }

    private Selector selector;
    private Router router;
    private SessionManager sessions;
    private List<VirtualServer> servers;

    public Server(List<Map<String, Object>> configs) {
        servers = parseConfigs(configs);
        router = new Router(servers);
        sessions = new SessionManager();
    }

    private List<VirtualServer> parseConfigs(List<Map<String, Object>> configs) {
        List<VirtualServer> list = new ArrayList<>();
        for (Map<String, Object> cfg : configs) {
            VirtualServer vs = new VirtualServer();
            vs.host = (String) cfg.getOrDefault("host", "127.0.0.1");
            vs.serverName = (String) cfg.getOrDefault("server_name", "");
            @SuppressWarnings("unchecked")
            List<Object> ports = (List<Object>) cfg.get("ports");
            vs.ports = ports.stream().mapToInt(o -> ((Number) o).intValue()).toArray();
            @SuppressWarnings("unchecked")
            Map<String, Object> eps = (Map<String, Object>) cfg.get("error_pages");
            if (eps != null) for (Map.Entry<String, Object> e : eps.entrySet())
                vs.errorPages.put(Integer.parseInt(e.getKey()), (String) e.getValue());
            if (cfg.containsKey("client_body_limit"))
                vs.clientBodyLimit = ((Number) cfg.get("client_body_limit")).intValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rs = (List<Map<String, Object>>) cfg.get("routes");
            if (rs != null) for (Map<String, Object> rc : rs) {
                Route r = new Route();
                r.path = (String) rc.get("path");
                @SuppressWarnings("unchecked")
                List<String> ms = (List<String>) rc.get("methods");
                r.methods = ms != null ? new HashSet<>(ms) : new HashSet<>(List.of("GET"));
                r.root = (String) rc.get("root");
                r.defaultFile = (String) rc.get("default_file");
                r.redirect = (String) rc.get("redirect");
                r.directoryListing = Boolean.TRUE.equals(rc.get("directory_listing"));
                @SuppressWarnings("unchecked")
                List<String> cg = (List<String>) rc.get("cgi_extensions");
                r.cgiExtensions = cg != null ? new HashSet<>(cg) : new HashSet<>();
                if (rc.containsKey("client_body_limit"))
                    r.clientBodyLimit = ((Number) rc.get("client_body_limit")).intValue();
                else r.clientBodyLimit = vs.clientBodyLimit;
                vs.routes.add(r);
            }
            list.add(vs);
        }
        return list;
    }

    public void start() throws IOException {
        selector = Selector.open();
        Set<Integer> usedPorts = new HashSet<>();
        for (VirtualServer vs : servers) {
            for (int port : vs.ports) {
                if (!usedPorts.add(port)) {
                    System.err.println("Warning: duplicate port " + port + " skipped");
                    continue;
                }
                try {
                    ServerSocketChannel ssc = ServerSocketChannel.open();
                    ssc.configureBlocking(false);
                    ssc.bind(new InetSocketAddress(vs.host, port));
                    ssc.register(selector, SelectionKey.OP_ACCEPT, vs);
                    System.out.println("Listening on " + vs.host + ":" + port + " (" + vs.serverName + ")");
                } catch (Exception e) {
                    System.err.println("Failed to bind " + vs.host + ":" + port + " - " + e.getMessage());
                }
            }
        }

        while (true) {
            try {
                selector.select(1000);
                for (SelectionKey key : selector.selectedKeys()) {
                    try {
                        if (key.isAcceptable()) handleAccept(key);
                        else if (key.isReadable()) handleRead(key);
                        else if (key.isWritable()) handleWrite(key);
                    } catch (Exception e) {
                        closeQuietly(key);
                    }
                }
                selector.selectedKeys().clear();
                long now = System.currentTimeMillis();
                for (SelectionKey key : selector.keys()) {
                    if (key.channel() instanceof SocketChannel) {
                        Connection c = (Connection) key.attachment();
                        if (c != null && now - c.lastActive > 30000) closeQuietly(key);
                    }
                }
                sessions.cleanup();
            } catch (Exception e) {
                System.err.println("Event loop error: " + e.getMessage());
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel sc = ssc.accept();
        sc.configureBlocking(false);
        Connection c = new Connection();
        c.server = (VirtualServer) key.attachment();
        sc.register(selector, SelectionKey.OP_READ, c);
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel sc = (SocketChannel) key.channel();
        Connection c = (Connection) key.attachment();
        c.lastActive = System.currentTimeMillis();
        c.buf.clear();
        int n = sc.read(c.buf);
        if (n == -1) { closeQuietly(key); return; }
        c.buf.flip();
        byte[] bytes = new byte[c.buf.limit()];
        c.buf.get(bytes);
        c.data.write(bytes);
        if (tryParse(c)) {
            process(c);
            prepareResponse(c);
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel sc = (SocketChannel) key.channel();
        Connection c = (Connection) key.attachment();
        c.lastActive = System.currentTimeMillis();
        if (c.buf == null || !c.buf.hasRemaining()) { closeQuietly(key); return; }
        sc.write(c.buf);
        if (!c.buf.hasRemaining()) closeQuietly(key);
    }

    private boolean tryParse(Connection c) {
        byte[] raw = c.data.toByteArray();
        int headerEnd = -1;
        for (int i = 0; i < raw.length - 3; i++)
            if (raw[i] == '\r' && raw[i+1] == '\n' && raw[i+2] == '\r' && raw[i+3] == '\n') { headerEnd = i; break; }
        if (headerEnd == -1) return false;

        String hdr = new String(raw, 0, headerEnd, StandardCharsets.ISO_8859_1);
        String[] lines = hdr.split("\r\n");
        if (lines.length == 0) return false;

        String[] rl = lines[0].split(" ", 3);
        if (rl.length < 3) { c.parseError = true; return true; }
        c.method = rl[0];
        String fullPath = rl[1];
        c.version = rl[2];
        int qi = fullPath.indexOf('?');
        if (qi >= 0) { c.path = fullPath.substring(0, qi); c.queryString = fullPath.substring(qi + 1); }
        else { c.path = fullPath; c.queryString = null; }
        try { c.path = URLDecoder.decode(c.path, "UTF-8"); } catch (Exception e) {}

        c.headers.clear();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) c.headers.put(lines[i].substring(0, colon).trim().toLowerCase(), lines[i].substring(colon + 1).trim());
        }

        int headerTotal = headerEnd + 4;
        int bodyBytes = raw.length - headerTotal;
        byte[] bodyPart = headerTotal < raw.length ? Arrays.copyOfRange(raw, headerTotal, raw.length) : new byte[0];
        String cl = c.headers.get("content-length");
        String te = c.headers.get("transfer-encoding");

        if (te != null && te.contains("chunked")) {
            try { c.body = parseChunked(bodyPart); if (c.body == null) return false; } catch (Exception e) { c.parseError = true; return true; }
            return c.body != null;
        } else if (cl != null) {
            try {
                int len = Integer.parseInt(cl.trim());
                if (bodyBytes < len) return false;
                c.body = bodyPart;
                return true;
            } catch (NumberFormatException e) { c.parseError = true; return true; }
        } else {
            c.body = new byte[0];
            return true;
        }
    }

    private byte[] parseChunked(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < data.length) {
            int crlf = -1;
            for (int i = pos; i < data.length - 1; i++)
                if (data[i] == '\r' && data[i+1] == '\n') { crlf = i; break; }
            if (crlf == -1) return null;
            String sizeStr = new String(data, pos, crlf - pos, StandardCharsets.US_ASCII).trim();
            int size = Integer.parseInt(sizeStr, 16);
            pos = crlf + 2;
            if (size == 0) return out.toByteArray();
            if (pos + size + 2 > data.length) return null;
            out.write(data, pos, size);
            pos += size + 2;
        }
        return null;
    }

    private void process(Connection c) {
        if (c.parseError) { sendErrorNow(c, 400); return; }
        c.route = router.match(c.server, c.path);
        if (c.route == null) { sendErrorNow(c, 404); return; }
        if (!c.route.methods.contains(c.method)) {
            sendErrorNow(c, 405);
            c.resHeaders.put("Allow", String.join(", ", c.route.methods));
            return;
        }
        if (c.route.redirect != null) {
            c.statusCode = 301; c.statusMessage = "Moved Permanently";
            c.resHeaders.put("Location", c.route.redirect);
            c.resBody = new byte[0]; return;
        }
        if (c.body != null && c.body.length > c.route.clientBodyLimit) { sendErrorNow(c, 413); return; }
        handleSession(c);
        switch (c.method) {
            case "GET": handleGet(c); break;
            case "POST": handlePost(c); break;
            case "DELETE": handleDelete(c); break;
            default: sendErrorNow(c, 405);
        }
    }

    private void handleSession(Connection c) {
        String cookie = c.headers.get("cookie");
        String sid = null;
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                part = part.trim();
                if (part.startsWith("session_id=")) { sid = part.substring(11); break; }
            }
        }
        if (sid == null || sessions.get(sid) == null) sid = sessions.createSession();
        c.resHeaders.put("Set-Cookie", "session_id=" + sid + "; Path=/; HttpOnly");
    }

    private void handleGet(Connection c) {
        if (hasCGI(c)) { runCGI(c); return; }
        serveFile(c);
    }

    private void handlePost(Connection c) {
        if (hasCGI(c)) { runCGI(c); return; }
        String filePath = resolvePath(c);
        Path p = Paths.get(filePath);
        if (Files.exists(p) && Files.isDirectory(p)) {
            serveFile(c); return;
        }
        if (!Files.exists(p) && c.route.path.equals("/")) { serveFile(c); return; }
        try {
            Files.createDirectories(p.getParent());
            Files.write(p, c.body);
            c.statusCode = 201; c.statusMessage = "Created";
            c.resBody = new byte[0];
        } catch (IOException e) { sendErrorNow(c, 500); }
    }

    private void handleDelete(Connection c) {
        String filePath = resolvePath(c);
        Path p = Paths.get(filePath);
        if (Files.exists(p) && Files.isDirectory(p)) {
            serveFile(c); return;
        }
        if (!Files.exists(p) && c.route.path.equals("/")) { serveFile(c); return; }
        try {
            if (Files.deleteIfExists(p)) {
                c.statusCode = 204; c.statusMessage = "No Content";
                c.resBody = new byte[0];
            } else sendErrorNow(c, 404);
        } catch (IOException e) { sendErrorNow(c, 500); }
    }

    private boolean hasCGI(Connection c) {
        if (c.route.cgiExtensions == null || c.route.cgiExtensions.isEmpty()) return false;
        String fp = resolvePath(c);
        int dot = fp.lastIndexOf('.');
        return dot >= 0 && c.route.cgiExtensions.contains(fp.substring(dot));
    }

    private void runCGI(Connection c) {
        try {
            byte[] out = CGIHandler.execute(resolvePath(c), c.method, c.headers, c.body, c.queryString);
            String outStr = new String(out, StandardCharsets.ISO_8859_1);
            int sep = outStr.indexOf("\r\n\r\n");
            if (sep < 0) sep = outStr.indexOf("\n\n");
            int hdrLen = sep >= 0 ? (outStr.charAt(Math.max(0,sep-1)) == '\r' ? sep + 4 : sep + 2) : -1;
            if (sep >= 0) {
                String hdrPart = outStr.substring(0, sep);
                for (String line : hdrPart.split("\r\n|\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String k = line.substring(0, colon).trim();
                        String v = line.substring(colon + 1).trim();
                        if (k.equalsIgnoreCase("Status")) {
                            String[] parts = v.split(" ", 2);
                            c.statusCode = Integer.parseInt(parts[0]);
                            c.statusMessage = parts.length > 1 ? parts[1] : "";
                        } else c.resHeaders.put(k, v);
                    }
                }
                c.resBody = Arrays.copyOfRange(out, hdrLen, out.length);
            } else c.resBody = out;
        } catch (Exception e) { sendErrorNow(c, 500); }
    }

    private void serveFile(Connection c) {
        String filePath = resolvePath(c);
        Path p = Paths.get(filePath);
        if (!Files.exists(p)) {
            if (c.route.defaultFile != null) p = Paths.get(filePath, c.route.defaultFile);
        }
        if (!Files.exists(p) || !Files.isReadable(p)) { sendErrorNow(c, 404); return; }
        if (Files.isDirectory(p)) {
            if (c.route.defaultFile != null) {
                Path def = p.resolve(c.route.defaultFile);
                if (Files.exists(def) && Files.isReadable(def)) { p = def; }
                else if (c.route.directoryListing) { listDir(c, p); return; }
                else { sendErrorNow(c, 403); return; }
            } else {
                if (c.route.directoryListing) { listDir(c, p); return; }
                sendErrorNow(c, 403); return;
            }
        }
        try {
            c.resBody = Files.readAllBytes(p);
            c.resHeaders.put("Content-Type", getMimeType(p.getFileName().toString()));
        } catch (IOException e) { sendErrorNow(c, 500); }
    }

    private void listDir(Connection c, Path dir) {
        StringBuilder html = new StringBuilder("<html><body><h1>Index of " + c.path + "</h1><ul>");
        try { Files.list(dir).forEach(p -> html.append("<li><a href=\"").append(c.path)
            .append(c.path.endsWith("/") ? "" : "/").append(p.getFileName()).append("\">")
            .append(p.getFileName()).append("</a></li>")); } catch (IOException e) {}
        html.append("</ul></body></html>");
        c.resBody = html.toString().getBytes();
        c.resHeaders.put("Content-Type", "text/html");
    }

    private String resolvePath(Connection c) {
        String rel = c.path.substring(c.route.path.length());
        if (rel.isEmpty()) rel = "/";
        Path root = Paths.get(c.route.root).normalize().toAbsolutePath();
        Path resolved = root.resolve("./" + rel).normalize();
        if (!resolved.startsWith(root)) resolved = root;
        return resolved.toString();
    }

    private void sendErrorNow(Connection c, int code) {
        c.statusCode = code;
        c.statusMessage = getStatusMessage(code);
        String ep = c.server.errorPages.get(code);
        if (ep != null) {
            try { c.resBody = Files.readAllBytes(Paths.get(ep));
                c.resHeaders.put("Content-Type", "text/html"); return; } catch (IOException e) {}
        }
        c.resBody = ("<html><body><h1>" + code + " " + c.statusMessage + "</h1></body></html>").getBytes();
        c.resHeaders.put("Content-Type", "text/html");
    }

    private void prepareResponse(Connection c) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(c.statusCode).append(" ").append(c.statusMessage).append("\r\n");
        if (!c.resHeaders.containsKey("Content-Type") && c.resBody != null)
            c.resHeaders.put("Content-Type", "text/html");
        if (!c.resHeaders.containsKey("Content-Length"))
            c.resHeaders.put("Content-Length", String.valueOf(c.resBody != null ? c.resBody.length : 0));
        c.resHeaders.put("Connection", "close");
        c.resHeaders.put("Server", "java-localserver");
        for (Map.Entry<String, String> e : c.resHeaders.entrySet())
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        sb.append("\r\n");
        byte[] hb = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try { baos.write(hb); if (c.resBody != null) baos.write(c.resBody); } catch (IOException e) {}
        c.buf = ByteBuffer.wrap(baos.toByteArray());
    }

    private void closeQuietly(SelectionKey key) {
        try { if (key.channel() instanceof SocketChannel) key.channel().close(); } catch (IOException e) {}
        key.cancel();
    }

    static String getStatusMessage(int code) {
        switch (code) {
            case 400: return "Bad Request"; case 403: return "Forbidden";
            case 404: return "Not Found"; case 405: return "Method Not Allowed";
            case 413: return "Request Entity Too Large"; case 500: return "Internal Server Error";
            case 301: return "Moved Permanently"; case 200: return "OK";
            case 201: return "Created"; case 204: return "No Content";
            default: return "Unknown";
        }
    }

    static String getMimeType(String name) {
        if (name.endsWith(".html")||name.endsWith(".htm")) return "text/html";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg")||name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }
}
