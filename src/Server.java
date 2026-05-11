import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
public class Server {
    private static final int READ_BUFFER_SIZE = 8192;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_CHUNK_LINE_BYTES = 8192;

    public static class Route {
        String path;
        Set<String> methods;
        String root, defaultFile, redirect;
        boolean directoryListing;
        Set<String> cgiExtensions;
        long clientBodyLimit = 1_048_576L;
    }

    public static class VirtualServer {
        String host = "127.0.0.1", serverName = "";
        int[] ports;
        Map<Integer, String> errorPages = new HashMap<>();
        long clientBodyLimit = 1_048_576L;
        List<Route> routes = new ArrayList<>();
    }

    static class Connection {
        ByteBuffer buf = ByteBuffer.allocate(READ_BUFFER_SIZE);
        ByteArrayOutputStream headerData = new ByteArrayOutputStream();
        List<VirtualServer> possibleServers = new ArrayList<>();
        VirtualServer server; Route route;
        String method, path, queryString, version;
        Map<String, String> headers = new LinkedHashMap<>();
        Path bodyFile;
        OutputStream bodyOut;
        long bodyLength;
        long expectedBodyLength = -1;
        boolean headersParsed;
        boolean chunked;
        boolean bodyComplete;
        int requestErrorStatus;
        int chunkState;
        long chunkRemaining;
        int chunkCrlfRead;
        StringBuilder chunkLine = new StringBuilder();
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
                vs.clientBodyLimit = ((Number) cfg.get("client_body_limit")).longValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rs = (List<Map<String, Object>>) cfg.get("routes");
            if (rs != null) for (Map<String, Object> rc : rs) {
                Route r = new Route();
                r.path = (String) rc.get("path");
                @SuppressWarnings("unchecked")
                List<String> ms = (List<String>) rc.get("methods");
                r.methods = ms != null ? new LinkedHashSet<>(ms) : new LinkedHashSet<>(List.of("GET"));
                r.root = (String) rc.get("root");
                r.defaultFile = (String) rc.get("default_file");
                r.redirect = (String) rc.get("redirect");
                r.directoryListing = Boolean.TRUE.equals(rc.get("directory_listing"));
                @SuppressWarnings("unchecked")
                List<String> cg = (List<String>) rc.get("cgi_extensions");
                r.cgiExtensions = cg != null ? new HashSet<>(cg) : new HashSet<>();
                if (rc.containsKey("client_body_limit"))
                    r.clientBodyLimit = ((Number) rc.get("client_body_limit")).longValue();
                else r.clientBodyLimit = vs.clientBodyLimit;
                vs.routes.add(r);
            }
            list.add(vs);
        }
        return list;
    }

    public void start() throws IOException {
        selector = Selector.open();
        Map<Integer, List<VirtualServer>> portMap = new LinkedHashMap<>();
        for (VirtualServer vs : servers) {
            for (int port : vs.ports) {
                portMap.computeIfAbsent(port, k -> new ArrayList<>()).add(vs);
            }
        }

        for (Map.Entry<Integer, List<VirtualServer>> entry : portMap.entrySet()) {
            int port = entry.getKey();
            List<VirtualServer> vsList = entry.getValue();
            try {
                ServerSocketChannel ssc = ServerSocketChannel.open();
                ssc.configureBlocking(false);
                ssc.bind(new InetSocketAddress(port));
                ssc.register(selector, SelectionKey.OP_ACCEPT, vsList);
            } catch (Exception e) {
                System.err.println("Failed to bind port " + port + " - " + e.getMessage());
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
        @SuppressWarnings("unchecked")
        List<VirtualServer> vsList = (List<VirtualServer>) key.attachment();
        c.possibleServers = vsList;
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
        boolean ready;
        try {
            ready = consumeRequest(c, bytes, 0, bytes.length);
        } catch (IOException e) {
            c.requestErrorStatus = 500;
            ready = true;
        }
        if (ready) {
            closeBodyWriter(c);
            process(c);
            prepareResponse(c);
            cleanupBody(c);
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

    private boolean consumeRequest(Connection c, byte[] bytes, int off, int len) throws IOException {
        if (!c.headersParsed) {
            c.headerData.write(bytes, off, len);
            if (c.headerData.size() > MAX_HEADER_BYTES) {
                return failRequest(c, 400);
            }
            byte[] raw = c.headerData.toByteArray();
            int headerEnd = findHeaderEnd(raw);
            if (headerEnd == -1) return false;
            if (!parseHeaders(c, raw, headerEnd)) return true;
            c.headersParsed = true;
            c.headerData = null;
            if (prepareBodyState(c)) return true;
            int bodyStart = headerEnd + 4;
            if (bodyStart < raw.length) {
                return consumeBody(c, raw, bodyStart, raw.length - bodyStart);
            }
            return c.bodyComplete || c.requestErrorStatus != 0 || c.parseError;
        }
        return consumeBody(c, bytes, off, len);
    }

    private int findHeaderEnd(byte[] raw) {
        int headerEnd = -1;
        for (int i = 0; i < raw.length - 3; i++)
            if (raw[i] == '\r' && raw[i+1] == '\n' && raw[i+2] == '\r' && raw[i+3] == '\n') { headerEnd = i; break; }
        return headerEnd;
    }

    private boolean parseHeaders(Connection c, byte[] raw, int headerEnd) {
        String hdr = new String(raw, 0, headerEnd, StandardCharsets.ISO_8859_1);
        String[] lines = hdr.split("\r\n");
        if (lines.length == 0) { failRequest(c, 400); return false; }

        String[] rl = lines[0].split(" ", 3);
        if (rl.length < 3) { failRequest(c, 400); return false; }
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

        selectServerAndRoute(c);
        return true;
    }

    private boolean prepareBodyState(Connection c) {
        if (c.route == null) return failRequest(c, 404);
        if (!c.route.methods.contains(c.method)) {
            c.resHeaders.put("Allow", String.join(", ", c.route.methods));
            return failRequest(c, 405);
        }
        if (c.route.redirect != null) {
            c.bodyComplete = true;
            return true;
        }

        String cl = c.headers.get("content-length");
        String te = c.headers.get("transfer-encoding");
        c.chunked = te != null && te.toLowerCase(Locale.ROOT).contains("chunked");

        if (c.chunked) {
            c.expectedBodyLength = -1;
            return false;
        }
        if (cl != null) {
            try {
                long len = Long.parseLong(cl.trim());
                if (len < 0) return failRequest(c, 400);
                if (len > c.route.clientBodyLimit) return failRequest(c, 413);
                c.expectedBodyLength = len;
                c.bodyComplete = len == 0;
                return c.bodyComplete;
            } catch (NumberFormatException e) { return failRequest(c, 400); }
        }
        c.expectedBodyLength = 0;
        c.bodyComplete = true;
        return true;
    }

    private boolean consumeBody(Connection c, byte[] data, int off, int len) throws IOException {
        if (c.requestErrorStatus != 0 || c.parseError || c.bodyComplete) return true;
        if (c.chunked) return consumeChunkedBody(c, data, off, len);

        long remaining = c.expectedBodyLength - c.bodyLength;
        int take = (int)Math.min(remaining, len);
        if (take > 0) writeBody(c, data, off, take);
        if (c.bodyLength == c.expectedBodyLength) {
            c.bodyComplete = true;
            closeBodyWriter(c);
            return true;
        }
        return false;
    }

    private boolean consumeChunkedBody(Connection c, byte[] data, int off, int len) throws IOException {
        int pos = off;
        int end = off + len;
        while (pos < end && c.requestErrorStatus == 0 && !c.parseError && !c.bodyComplete) {
            if (c.chunkState == 0) {
                while (pos < end) {
                    byte b = data[pos++];
                    c.chunkLine.append((char)(b & 0xff));
                    if (c.chunkLine.length() > MAX_CHUNK_LINE_BYTES) return failRequest(c, 400);
                    if (b == '\n') {
                        String line = stripCrlf(c.chunkLine.toString());
                        c.chunkLine.setLength(0);
                        int semicolon = line.indexOf(';');
                        if (semicolon >= 0) line = line.substring(0, semicolon);
                        line = line.trim();
                        if (line.isEmpty()) return failRequest(c, 400);
                        try {
                            c.chunkRemaining = Long.parseLong(line, 16);
                        } catch (NumberFormatException e) {
                            return failRequest(c, 400);
                        }
                        if (c.chunkRemaining < 0) return failRequest(c, 400);
                        c.chunkState = c.chunkRemaining == 0 ? 3 : 1;
                        break;
                    }
                }
            } else if (c.chunkState == 1) {
                int take = (int)Math.min(c.chunkRemaining, end - pos);
                if (take == 0) break;
                if (take > c.route.clientBodyLimit - c.bodyLength) return failRequest(c, 413);
                writeBody(c, data, pos, take);
                pos += take;
                c.chunkRemaining -= take;
                if (c.chunkRemaining == 0) {
                    c.chunkState = 2;
                    c.chunkCrlfRead = 0;
                }
            } else if (c.chunkState == 2) {
                while (pos < end && c.chunkCrlfRead < 2) {
                    byte b = data[pos++];
                    if (c.chunkCrlfRead == 0 && b != '\r') return failRequest(c, 400);
                    if (c.chunkCrlfRead == 1 && b != '\n') return failRequest(c, 400);
                    c.chunkCrlfRead++;
                }
                if (c.chunkCrlfRead == 2) c.chunkState = 0;
            } else {
                while (pos < end) {
                    byte b = data[pos++];
                    c.chunkLine.append((char)(b & 0xff));
                    if (c.chunkLine.length() > MAX_CHUNK_LINE_BYTES) return failRequest(c, 400);
                    if (b == '\n') {
                        String line = stripCrlf(c.chunkLine.toString());
                        c.chunkLine.setLength(0);
                        if (line.isEmpty()) {
                            c.bodyComplete = true;
                            closeBodyWriter(c);
                            return true;
                        }
                        break;
                    }
                }
            }
        }
        return c.bodyComplete || c.requestErrorStatus != 0 || c.parseError;
    }

    private String stripCrlf(String line) {
        if (line.endsWith("\n")) line = line.substring(0, line.length() - 1);
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        return line;
    }

    private void writeBody(Connection c, byte[] data, int off, int len) throws IOException {
        if (len <= 0) return;
        if (c.bodyOut == null) {
            c.bodyFile = Files.createTempFile("java-localserver-body-", ".tmp");
            c.bodyOut = Files.newOutputStream(c.bodyFile, StandardOpenOption.WRITE);
        }
        c.bodyOut.write(data, off, len);
        c.bodyLength += len;
    }

    private void closeBodyWriter(Connection c) {
        if (c.bodyOut != null) {
            try { c.bodyOut.close(); } catch (IOException e) {}
            c.bodyOut = null;
        }
    }

    private void cleanupBody(Connection c) {
        closeBodyWriter(c);
        if (c.bodyFile != null) {
            try { Files.deleteIfExists(c.bodyFile); } catch (IOException e) {}
            c.bodyFile = null;
        }
    }

    private boolean failRequest(Connection c, int status) {
        if (status == 400) c.parseError = true;
        c.requestErrorStatus = status;
        c.bodyComplete = true;
        closeBodyWriter(c);
        if (c.server == null) selectDefaultServer(c);
        return true;
    }

    private void selectDefaultServer(Connection c) {
        if (c.server == null && c.possibleServers != null && !c.possibleServers.isEmpty())
            c.server = c.possibleServers.get(0);
    }

    private void selectServerAndRoute(Connection c) {
        String hostHeader = c.headers.get("host");
        if (hostHeader != null && hostHeader.contains(":")) {
            hostHeader = hostHeader.split(":", 2)[0];
        }
        String finalHost = hostHeader != null ? hostHeader.trim() : "";
        c.server = c.possibleServers.stream()
            .filter(vs -> vs.serverName.equalsIgnoreCase(finalHost))
            .findFirst()
            .orElse(c.possibleServers.get(0));
        c.route = router.match(c.server, c.path);
    }

    private void process(Connection c) {
        selectDefaultServer(c);
        if (c.parseError) { sendErrorNow(c, 400); return; }
        if (c.requestErrorStatus != 0) { sendErrorNow(c, c.requestErrorStatus); return; }
        if (c.route == null) c.route = router.match(c.server, c.path);
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
        if (c.bodyLength > c.route.clientBodyLimit) { sendErrorNow(c, 413); return; }
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
        if (!isUploadRoute(c)) { serveFile(c); return; }
        String filePath = resolvePath(c);
        Path p = Paths.get(filePath);
        if (Files.exists(p) && Files.isDirectory(p)) {
            serveFile(c); return;
        }
        if (!Files.exists(p) && c.route.path.equals("/")) { serveFile(c); return; }
        try {
            Files.createDirectories(p.getParent());
            saveBodyToFile(c, p);
            c.statusCode = 201; c.statusMessage = "Created";
            c.resBody = ("<html><body><h1>201 Created</h1><p>" + c.method + " file uploaded successfully</p></body></html>").getBytes();
            c.resHeaders.put("Content-Type", "text/html; charset=utf-8");
            System.out.println("Saved file to " + p.toString());
        } catch (IOException e) { sendErrorNow(c, 500); }
    }

    private void handleDelete(Connection c) {
        if (hasCGI(c)) { runCGI(c); return; }
        if (!isUploadRoute(c)) { serveFile(c); return; }
        String filePath = resolvePath(c);
        Path p = Paths.get(filePath);
        if (Files.exists(p) && Files.isDirectory(p)) {
            serveFile(c); return;
        }
        if (!Files.exists(p) && c.route.path.equals("/")) { serveFile(c); return; }
        try {
            if (Files.deleteIfExists(p)) {
                c.statusCode = 200; c.statusMessage = "OK";
                c.resBody = ("<html><body><h1>200 OK</h1><p>" + c.method + " file deleted successfully</p></body></html>").getBytes();
                c.resHeaders.put("Content-Type", "text/html; charset=utf-8");
            } else sendErrorNow(c, 404);
        } catch (IOException e) { sendErrorNow(c, 500); }
    }

    private boolean isUploadRoute(Connection c) {
        return c.route != null && "/upload".equals(c.route.path);
    }

    private boolean hasCGI(Connection c) {
        if (c.route.cgiExtensions == null || c.route.cgiExtensions.isEmpty()) return false;
        String fp = resolvePath(c);
        int dot = fp.lastIndexOf('.');
        return dot >= 0 && c.route.cgiExtensions.contains(fp.substring(dot));
    }

    private void runCGI(Connection c) {
        try {    
            byte[] out = CGIHandler.execute(resolvePath(c), c.method, c.headers, c.bodyFile, c.bodyLength, c.queryString);
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
        } catch (Exception e) { 
              if (e instanceof FileNotFoundException) sendErrorNow(c, 404);
              else  
            sendErrorNow(c, 500); }
    }

    private void saveBodyToFile(Connection c, Path target) throws IOException {
         closeBodyWriter(c);
        if (c.bodyFile == null) {
            Files.write(target, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return;
        }
        try {
            Files.move(c.bodyFile, target, StandardCopyOption.REPLACE_EXISTING);
            c.bodyFile = null;
        } catch (IOException moveError) {
            Files.copy(c.bodyFile, target, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(c.bodyFile);
            c.bodyFile = null;
        }
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
        selectDefaultServer(c);
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
        Object attachment = key.attachment();
        if (attachment instanceof Connection) cleanupBody((Connection)attachment);
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
