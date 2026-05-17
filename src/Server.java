import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class Server {
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final long TIMEOUT_MS = 30_000;
    private static final long CGI_TIMEOUT_MS = 10_000;
    private static final ByteBuffer READ_BUF = ByteBuffer.allocate(8192);

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
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        List<VirtualServer> candidates;
        VirtualServer server;
        Route route;
        String method, path, query;
        Map<String, String> headers = new LinkedHashMap<>();
        boolean headersParsed, bodyDone;
        int localPort;

        Path bodyFile;
        OutputStream bodyOut;
        long bodyLen, contentLen = -1;
        boolean chunked;
        int chunkState;        // 0=read line (size or inter-chunk CRLF), 1=read data, 2=read trailer
        long chunkLeft;
        StringBuilder chunkLine = new StringBuilder();

        int errorStatus;
        int status = 200;
        String statusMsg = "OK";
        Map<String, String> resHeaders = new LinkedHashMap<>();
        byte[] resBody;
        ByteBuffer writeBuf;
        long lastActive = System.currentTimeMillis();

        // streaming file response
        Path responseFile;
        FileChannel fileChannel;
        long fileOffset, fileSize;

        // CGI process state
        Process cgiProcess;
        InputStream cgiInputStream;
        ByteArrayOutputStream cgiOutput;
        long cgiDeadline;
    }

    private Selector selector;
    private final Router router;
    private final SessionManager sessions;
    private final List<VirtualServer> servers;

    public Server(List<Map<String, Object>> configs) {
        this.servers = parseConfigs(configs);
        this.router = new Router(servers);
        this.sessions = new SessionManager();
    }

    // ---------- config ----------
    private List<VirtualServer> parseConfigs(List<Map<String, Object>> configs) {
        List<VirtualServer> list = new ArrayList<>();
        for (int idx = 0; idx < configs.size(); idx++) {
            Map<String, Object> cfg = configs.get(idx);
            try {
                VirtualServer vs = new VirtualServer();
                vs.host = (String) cfg.getOrDefault("host", "127.0.0.1");
                vs.serverName = (String) cfg.getOrDefault("server_name", "");

                // --- Validate ports ---
                @SuppressWarnings("unchecked")
                List<Object> ports = (List<Object>) cfg.get("ports");
                if (ports == null || ports.isEmpty()) {
                    throw new IllegalArgumentException("No ports configured");
                }
                vs.ports = ports.stream().mapToInt(o -> ((Number) o).intValue()).toArray();

                Set<Integer> seenPorts = new HashSet<>();
                for (int port : vs.ports) {
                    if (port < 1 || port > 65535) {
                        throw new IllegalArgumentException(
                            "Invalid port " + port + ". Port must be between 1 and 65535.");
                    }
                    if (!seenPorts.add(port)) {
                        throw new IllegalArgumentException(
                            "Duplicate port " + port + ". Each port must appear only once per server block.");
                    }
                }

                // --- Error pages ---
                @SuppressWarnings("unchecked")
                Map<String, Object> eps = (Map<String, Object>) cfg.get("error_pages");
                if (eps != null) eps.forEach((k, v) -> vs.errorPages.put(Integer.parseInt(k), (String) v));

                // --- Client body limit ---
                if (cfg.containsKey("client_body_limit"))
                    vs.clientBodyLimit = ((Number) cfg.get("client_body_limit")).longValue();

                // --- Routes ---
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rs = (List<Map<String, Object>>) cfg.get("routes");
                if (rs == null || rs.isEmpty()) {
                    throw new IllegalArgumentException("No routes configured");
                }
                for (Map<String, Object> rc : rs) {
                    Route r = new Route();
                    r.path = (String) rc.get("path");
                    if (r.path == null || r.path.isEmpty()) {
                        throw new IllegalArgumentException("Route missing 'path' field");
                    }
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
                    r.clientBodyLimit = rc.containsKey("client_body_limit")
                            ? ((Number) rc.get("client_body_limit")).longValue()
                            : vs.clientBodyLimit;
                    vs.routes.add(r);
                }
                list.add(vs);
                System.out.println("Loaded server '" + vs.serverName + "' on ports "
                        + Arrays.toString(vs.ports));
            } catch (Exception e) {
                String name = cfg.containsKey("server_name") ? (String) cfg.get("server_name") : "server #" + (idx + 1);
                System.err.println("Warning: skipping server '" + name
                        + "' due to configuration error: " + e.getMessage());
            }
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException(
                "No valid server configurations found. All server blocks had errors.");

        }
        return list;
    }

    // ---------- event loop ----------
    public void start() throws IOException {
        selector = Selector.open();
        Map<Integer, List<VirtualServer>> portMap = new LinkedHashMap<>();
        for (VirtualServer vs : servers)
            for (int port : vs.ports)
                portMap.computeIfAbsent(port, k -> new ArrayList<>()).add(vs);

        for (Map.Entry<Integer, List<VirtualServer>> e : portMap.entrySet()) {
            try {
                ServerSocketChannel ssc = ServerSocketChannel.open();
                ssc.configureBlocking(false);
                ssc.bind(new InetSocketAddress(e.getKey()));
                ssc.register(selector, SelectionKey.OP_ACCEPT, e.getValue());
                System.out.println("Server running on port " + e.getKey() + " Host: " + e.getValue().stream().map(vs -> vs.serverName).collect(Collectors.joining(", ")));
            } catch (Exception ex) {
                System.err.println("Failed to bind port " + e.getKey() + " - " + ex.getMessage());
            }
        }
    
        while (true) {
            selector.select(100);

            for (SelectionKey key : selector.selectedKeys()) {
                try {
                    if (key.isAcceptable()) accept(key);
                    else if (key.isReadable()) read(key);
                    else if (key.isWritable()) write(key);
                } catch (Exception e) { close(key); }
            }
            selector.selectedKeys().clear();

            // poll running CGI processes
            for (SelectionKey key : selector.keys()) {
                if (!(key.channel() instanceof SocketChannel)) continue;
                Connection c = (Connection) key.attachment();
                if (c != null && c.cgiProcess != null) pollCGI(c, key);
            }

            long now = System.currentTimeMillis();
            for (SelectionKey key : selector.keys()) {
                if (key.channel() instanceof SocketChannel) {
                    Connection c = (Connection) key.attachment();
                    if (c != null && c.cgiProcess == null && now - c.lastActive > TIMEOUT_MS) close(key);
                }
            }
            sessions.cleanup();
        }
    }
    private void accept(SelectionKey key) throws IOException {
        SocketChannel sc = ((ServerSocketChannel) key.channel()).accept();
        sc.configureBlocking(false);
        Connection c = new Connection();
        @SuppressWarnings("unchecked")
        List<VirtualServer> vsList = (List<VirtualServer>) key.attachment();
        c.candidates = vsList;
        c.localPort = ((InetSocketAddress) sc.getLocalAddress()).getPort();
        sc.register(selector, SelectionKey.OP_READ, c);
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel sc = (SocketChannel) key.channel();
        Connection c = (Connection) key.attachment();
        c.lastActive = System.currentTimeMillis();

        READ_BUF.clear();
        int n = sc.read(READ_BUF);
        if (n == -1) { close(key); return; }
        READ_BUF.flip();
        byte[] data = new byte[READ_BUF.limit()];
        READ_BUF.get(data);

        try { feed(c, data); }
        catch (Exception e) {
            System.err.println("Error processing request: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            c.errorStatus = 500;
            c.bodyDone = true;
        }

        if (c.bodyDone || c.errorStatus != 0) {
            closeBodyOut(c);
            process(c, key);
            if (c.cgiProcess == null) {
                buildResponse(c);
                cleanupBody(c);
                key.interestOps(SelectionKey.OP_WRITE);
            }
        }
    }

    private void write(SelectionKey key) throws IOException {
        SocketChannel sc = (SocketChannel) key.channel();
        Connection c = (Connection) key.attachment();
        c.lastActive = System.currentTimeMillis();

        // write headers (and in-memory body if no streaming file)
        if (c.writeBuf != null && c.writeBuf.hasRemaining()) {
            sc.write(c.writeBuf);
            if (c.writeBuf.hasRemaining()) return;
        }

        // stream file content if present
        if (c.fileChannel != null) {
            long transferred = c.fileChannel.transferTo(c.fileOffset, 65536, sc);
            if (transferred > 0) c.fileOffset += transferred;
            if (c.fileOffset >= c.fileSize) {
                close(key);
            }
            return;
        }

        // everything written
        close(key);
    }

    private void close(SelectionKey key) {
        if (key.attachment() instanceof Connection) {
            Connection c = (Connection) key.attachment();
            cleanupBody(c);
            if (c.fileChannel != null) {
                try { c.fileChannel.close(); } catch (IOException e) {}
                c.fileChannel = null;
            }
            if (c.cgiProcess != null) {
                c.cgiProcess.destroyForcibly();
                c.cgiProcess = null;
                c.cgiInputStream = null;
                c.cgiOutput = null;
            }
        }
        try { key.channel().close(); } catch (IOException e) {}
        key.cancel();
    }

    // ---------- request parsing ----------
    private void feed(Connection c, byte[] data) throws IOException {
        int off = 0;
        if (!c.headersParsed) {
            c.buf.write(data);
            if (c.buf.size() > MAX_HEADER_BYTES) { fail(c, 400); return; }
            byte[] all = c.buf.toByteArray();
            int end = findHeaderEnd(all);
            if (end == -1) return;
            if (!parseHeaders(c, all, end)) return;
            c.headersParsed = true;
            c.buf = null;
            if (!prepareBody(c)) return;
            off = end + 4;
            data = all;
        }
        consumeBody(c, data, off, data.length - off);
    }

    private int findHeaderEnd(byte[] raw) {
        for (int i = 0; i + 3 < raw.length; i++)
            if (raw[i] == '\r' && raw[i+1] == '\n' && raw[i+2] == '\r' && raw[i+3] == '\n') return i;
        return -1;
    }

    private boolean parseHeaders(Connection c, byte[] raw, int end) {
        String hdr = new String(raw, 0, end, StandardCharsets.ISO_8859_1);
        String[] lines = hdr.split("\r\n");
        if (lines.length == 0) { fail(c, 400); return false; }
        String[] rl = lines[0].split(" ", 3);
        if (rl.length < 3) { fail(c, 400); return false; }
        c.method = rl[0];
        String full = rl[1];
        int q = full.indexOf('?');
        if (q >= 0) { c.path = full.substring(0, q); c.query = full.substring(q + 1); }
        else { c.path = full; c.query = null; }
        try { c.path = URLDecoder.decode(c.path, "UTF-8"); } catch (Exception ignored) {}
        c.headers.clear();
        for (int i = 1; i < lines.length; i++) {
            int col = lines[i].indexOf(':');
            if (col > 0) c.headers.put(lines[i].substring(0, col).trim().toLowerCase(),
                    lines[i].substring(col + 1).trim());
        }
        selectServer(c);
        if (c.errorStatus != 0) return false;
        return true;
    }

    private void selectServer(Connection c) {
        String host = c.headers.getOrDefault("host", "");
        int  hostPort = 0 ; 
        if (host.contains(":")) {
            String[] parts = host.split(":", 2);
            host = parts[0];
            try {
                hostPort = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                fail(c, 400);
                return;
            }
        }
       
        final String h = host.trim();
        c.server = c.candidates.stream()
                .filter(v -> v.serverName.equalsIgnoreCase(h))
                .findFirst()
                .orElse(c.candidates.get(0));


            boolean validPort = false ;
              for  (int  port : c.server.ports){
                if  (port  == hostPort ){
                    validPort = true ; 
                    break  ;
                }
              }
              if  (!validPort ) {
                fail(c, 400);
                return;
              }
        c.route = router.match(c.server, c.path);
    }

    private boolean prepareBody(Connection c) {
        if (c.route == null) { fail(c, 404); return false; }
        if (!c.route.methods.contains(c.method)) { fail(c, 405); return false; }
        if (c.route.redirect != null) { c.bodyDone = true; return false; }

        String te = c.headers.get("transfer-encoding");
        c.chunked = te != null && te.toLowerCase(Locale.ROOT).contains("chunked");
        if (c.chunked) return true;

        String cl = c.headers.get("content-length");
        if (cl == null) { c.contentLen = 0; c.bodyDone = true; return false; }
        try {
            c.contentLen = Long.parseLong(cl.trim());
            if (c.contentLen < 0) { fail(c, 400); return false; }
            if (c.contentLen > c.route.clientBodyLimit) { fail(c, 413); return false; }
            if (c.contentLen == 0) { c.bodyDone = true; return false; }
            return true;
        } catch (NumberFormatException e) { fail(c, 400); return false; }
    }

    private void fail(Connection c, int code) {
        c.errorStatus = code;
        c.bodyDone = true;
        closeBodyOut(c);
    }

    private void consumeBody(Connection c, byte[] data, int off, int len) throws IOException {
        if (c.errorStatus != 0 || c.bodyDone || len <= 0) return;
        if (c.chunked) { consumeChunked(c, data, off, len); return; }
        int take = (int) Math.min(c.contentLen - c.bodyLen, len);
        if (take > 0) writeBody(c, data, off, take);
        if (c.bodyLen == c.contentLen) { c.bodyDone = true; closeBodyOut(c); }
    }

    private void consumeChunked(Connection c, byte[] data, int off, int len) throws IOException {
        int p = off, end = off + len;
        while (p < end && c.errorStatus == 0 && !c.bodyDone) {
            if (c.chunkState == 1) {
                // reading chunk data
                int take = (int) Math.min(c.chunkLeft, end - p);
                if (c.bodyLen + take > c.route.clientBodyLimit) { fail(c, 413); return; }
                writeBody(c, data, p, take);
                p += take;
                c.chunkLeft -= take;
                if (c.chunkLeft == 0) c.chunkState = 0; // back to reading a line (CRLF after chunk)
                continue;
            }
            // state 0 or 2: read one line into chunkLine
            String line = null;
            while (p < end) {
                char ch = (char) (data[p++] & 0xff);
                if (ch == '\n') {
                    line = c.chunkLine.toString();
                    if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                    c.chunkLine.setLength(0);
                    break;
                }
                c.chunkLine.append(ch);
                if (c.chunkLine.length() > 8192) { fail(c, 400); return; }
            }
            if (line == null) return; // need more data
            if (c.chunkState == 2) {
                // trailer: empty line = end of body
                if (line.isEmpty()) { c.bodyDone = true; closeBodyOut(c); return; }
                // else: ignore trailer header, keep reading
                continue;
            }
            // state 0: either the CRLF after previous chunk data (empty line) or a size line
            if (line.isEmpty()) continue;
            int sc = line.indexOf(';');
            if (sc >= 0) line = line.substring(0, sc);
            line = line.trim();
            try { c.chunkLeft = Long.parseLong(line, 16); }
            catch (NumberFormatException e) { fail(c, 400); return; }
            if (c.chunkLeft < 0) { fail(c, 400); return; }
            c.chunkState = c.chunkLeft == 0 ? 2 : 1; // 0 size -> read trailer, else read data
        }
    }

    private void writeBody(Connection c, byte[] data, int off, int len) throws IOException {
        if (len <= 0) return;
        if (c.bodyOut == null) {
            Path tmpDir = Paths.get(".tmp");
            Files.createDirectories(tmpDir);
            c.bodyFile = Files.createTempFile(tmpDir, "body-", ".tmp");
            c.bodyOut = new BufferedOutputStream(Files.newOutputStream(c.bodyFile), 65536);
        }
        c.bodyOut.write(data, off, len);
        c.bodyLen += len;
    }

    private void closeBodyOut(Connection c) {
        if (c.bodyOut != null) { try { c.bodyOut.close(); } catch (IOException e) {} c.bodyOut = null; }
    }

    private void cleanupBody(Connection c) {
        closeBodyOut(c);
        if (c.bodyFile != null) { try { Files.deleteIfExists(c.bodyFile); } catch (IOException e) {} c.bodyFile = null; }
    }

    // ---------- dispatch ----------
    private void process(Connection c, SelectionKey key) {
        if (c.server == null) c.server = c.candidates.get(0);
        if (c.errorStatus != 0) {
            if (c.errorStatus == 405 && c.route != null)
                c.resHeaders.put("Allow", String.join(", ", c.route.methods));
            sendError(c, c.errorStatus); return;
        }
        if (c.route.redirect != null) {
            c.status = 301; c.statusMsg = "Moved Permanently";
            c.resHeaders.put("Location", c.route.redirect);
            c.resBody = new byte[0];
            return;
        }
        applySession(c);

        if (isCGI(c)) { runCGI(c, key); return; }

        boolean upload = "/upload".equals(c.route.path);
        switch (c.method) {
            case "GET":    serveFile(c); break;
            case "POST":   if (upload) doUpload(c); else serveFile(c); break;
            case "DELETE": if (upload) doDelete(c); else serveFile(c); break;
            default:       sendError(c, 405);
        }
    }

    private void applySession(Connection c) {
        String cookie = c.headers.get("cookie");
        String sid = null;
        if (cookie != null) for (String part : cookie.split(";")) {
            part = part.trim();
            if (part.startsWith("session_id=")) { sid = part.substring(11); break; }
        }
        if (sid == null || sessions.get(sid) == null) sid = sessions.createSession();
        c.resHeaders.put("Set-Cookie", "session_id=" + sid + "; Path=/; HttpOnly");
    }

    private boolean isCGI(Connection c) {
        if (c.route.cgiExtensions == null || c.route.cgiExtensions.isEmpty()) return false;
        String f = resolvePath(c);
        int dot = f.lastIndexOf('.');
        return dot >= 0 && c.route.cgiExtensions.contains(f.substring(dot));
    }

    private void runCGI(Connection c, SelectionKey key) {
        try {
            Process p = CGIHandler.start(resolvePath(c), c.method, c.headers,
                    c.bodyFile, c.bodyLen, c.query);
            c.cgiProcess = p;
            c.cgiInputStream = p.getInputStream();
            c.cgiOutput = new ByteArrayOutputStream();
            c.cgiDeadline = System.currentTimeMillis() + CGI_TIMEOUT_MS;
            key.interestOps(0); // suspend read/write while CGI runs
        } catch (FileNotFoundException e) {
            sendError(c, 404);
        } catch (Exception e) {
            System.err.println("CGI start error: " + e.getMessage());
            sendError(c, 500);
        }
    }

    private void pollCGI(Connection c, SelectionKey key) {
        // read available output without blocking
        try {
            int avail = c.cgiInputStream.available();
            if (avail > 0) {
                byte[] buf = new byte[Math.min(avail, 8192)];
                int n = c.cgiInputStream.read(buf);
                if (n > 0) c.cgiOutput.write(buf, 0, n);
            }
        } catch (IOException e) {}

        // check timeout
        if (System.currentTimeMillis() > c.cgiDeadline) {
            c.cgiProcess.destroyForcibly();
            System.err.println("CGI timeout after " + CGI_TIMEOUT_MS + "ms");
            finishCGI(c, key, true);
            return;
        }

        // check if process finished
        try {
            c.cgiProcess.exitValue(); // throws IllegalThreadStateException if still running
            // drain remaining output
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = c.cgiInputStream.read(buf)) != -1) {
                    c.cgiOutput.write(buf, 0, n);
                }
            } catch (IOException e) {}
            finishCGI(c, key, false);
        } catch (IllegalThreadStateException e) {
            // still running, will poll again next iteration
        }
    }

    private void finishCGI(Connection c, SelectionKey key, boolean timedOut) {
        if (timedOut) {
            sendError(c, 504);
        } else {
            parseCGIOutput(c, c.cgiOutput.toByteArray());
        }
        c.cgiProcess = null;
        c.cgiInputStream = null;
        c.cgiOutput = null;
        buildResponse(c);
        cleanupBody(c);
        if (key.isValid()) key.interestOps(SelectionKey.OP_WRITE);
    }

    private void parseCGIOutput(Connection c, byte[] out) {
        String s = new String(out, StandardCharsets.ISO_8859_1);
        int sep = s.indexOf("\r\n\r\n");
        int hdrEnd = sep >= 0 ? sep + 4 : -1;
        if (sep < 0) { sep = s.indexOf("\n\n"); hdrEnd = sep >= 0 ? sep + 2 : -1; }
        if (sep >= 0) {
            for (String line : s.substring(0, sep).split("\r?\n")) {
                int col = line.indexOf(':');
                if (col <= 0) continue;
                String k = line.substring(0, col).trim();
                String v = line.substring(col + 1).trim();
                if (k.equalsIgnoreCase("Status")) {
                    String[] parts = v.split(" ", 2);
                    try { c.status = Integer.parseInt(parts[0]); } catch (NumberFormatException e) {}
                    c.statusMsg = parts.length > 1 ? parts[1] : "";
                } else c.resHeaders.put(k, v);
            }
            c.resBody = Arrays.copyOfRange(out, hdrEnd, out.length);
        } else c.resBody = out;
    }

    private void doUpload(Connection c) {
        Path p = Paths.get(resolvePath(c));
        if (Files.isDirectory(p)) { serveFile(c); return; }
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            if (c.bodyFile != null) {
                try { Files.move(c.bodyFile, p, StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException e) {
                    Files.copy(c.bodyFile, p, StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(c.bodyFile);
                }
                c.bodyFile = null;
            } else {
                Files.write(p, new byte[0]);
            }
            c.status = 201; c.statusMsg = "Created";
            c.resBody = "<html><body><h1>201 Created</h1><p>File uploaded</p></body></html>".getBytes();
            c.resHeaders.put("Content-Type", "text/html; charset=utf-8");
        } catch (IOException e) { sendError(c, 500); }
    }

    private void doDelete(Connection c) {
        Path p = Paths.get(resolvePath(c));
        if (Files.isDirectory(p)) { serveFile(c); return; }
        try {
            if (Files.deleteIfExists(p)) {
                c.status = 200; c.statusMsg = "OK";
                c.resBody = "<html><body><h1>200 OK</h1><p>File deleted</p></body></html>".getBytes();
                c.resHeaders.put("Content-Type", "text/html; charset=utf-8");
            } else sendError(c, 404);
        } catch (IOException e) { sendError(c, 500); }
    }

    private void serveFile(Connection c) {
        Path p = Paths.get(resolvePath(c));
        if (Files.isDirectory(p)) {
            if (c.route.directoryListing) { listDir(c, p); return; }
            Path def = c.route.defaultFile != null ? p.resolve(c.route.defaultFile) : null;
            if (def != null && Files.isReadable(def)) p = def;
            else { sendError(c, 403); return; }
        }
        if (!Files.exists(p) || !Files.isReadable(p)) { sendError(c, 404); return; }
        try {
            c.responseFile = p;
            c.fileSize = Files.size(p);
            c.fileOffset = 0;
            c.resHeaders.put("Content-Type", mimeType(p.getFileName().toString()));
        } catch (IOException e) { sendError(c, 500); }
    }

    private void listDir(Connection c, Path dir) {
        StringBuilder h = new StringBuilder("<html><body><h1>Index of ")
                .append(c.path).append("</h1><ul>");
        try {
            Files.list(dir).forEach(p -> h.append("<li><a href=\"").append(c.path)
                    .append(c.path.endsWith("/") ? "" : "/").append(p.getFileName()).append("\">")
                    .append(p.getFileName()).append("</a></li>"));
        } catch (IOException e) {}
        h.append("</ul></body></html>");
        c.resBody = h.toString().getBytes();
        c.resHeaders.put("Content-Type", "text/html");
    }

    private String resolvePath(Connection c) {
        String rel = c.path.substring(c.route.path.length());
        if (rel.isEmpty()) rel = "/";
        Path root = Paths.get(c.route.root).normalize().toAbsolutePath();
        Path p = root.resolve("./" + rel).normalize();
        return (p.startsWith(root) ? p : root).toString();
    }

    // ---------- response ----------
    private void sendError(Connection c, int code) {
        c.status = code;
        c.statusMsg = statusMsg(code);
        String ep = c.server.errorPages.get(code);
        if (ep != null) {
            try {
                c.resBody = Files.readAllBytes(Paths.get(ep));
                c.resHeaders.put("Content-Type", "text/html");
                return;
            } catch (IOException e) {}
        }
        c.resBody = ("<html><body><h1>" + code + " " + c.statusMsg + "</h1></body></html>").getBytes();
        c.resHeaders.put("Content-Type", "text/html");
    }

    private void buildResponse(Connection c) {
        c.resHeaders.putIfAbsent("Content-Type", "text/html");
        c.resHeaders.put("Connection", "close");
        c.resHeaders.put("Server", "java-localserver");

        if (c.responseFile != null) {
            // streaming file: headers-only buffer, file sent via FileChannel
            c.resHeaders.put("Content-Length", String.valueOf(c.fileSize));
            try {
                c.fileChannel = FileChannel.open(c.responseFile, StandardOpenOption.READ);
            } catch (IOException e) {
                c.responseFile = null;
                c.fileChannel = null;
                sendError(c, 500);
                // fall through to in-memory path
            }
        }

        if (c.responseFile == null) {
            // in-memory response (errors, uploads, directory listings, small pages)
            if (c.resBody == null) c.resBody = new byte[0];
            c.resHeaders.put("Content-Length", String.valueOf(c.resBody.length));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(c.status).append(' ').append(c.statusMsg).append("\r\n");
        for (Map.Entry<String, String> e : c.resHeaders.entrySet())
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        sb.append("\r\n");

        byte[] hb = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        if (c.responseFile != null && c.fileChannel != null) {
            // headers only — file body streamed in write()
            c.writeBuf = ByteBuffer.wrap(hb);
        } else {
            // headers + in-memory body
            byte[] full = new byte[hb.length + c.resBody.length];
            System.arraycopy(hb, 0, full, 0, hb.length);
            System.arraycopy(c.resBody, 0, full, hb.length, c.resBody.length);
            c.writeBuf = ByteBuffer.wrap(full);
        }
    }

    // ---------- helpers ----------
    static String statusMsg(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 301: return "Moved Permanently";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Request Entity Too Large";
            case 500: return "Internal Server Error";
            case 504: return "Gateway Timeout";
            default:  return "Unknown";
        }
    }

    static String mimeType(String name) {
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html";
        if (name.endsWith(".css"))  return "text/css";
        if (name.endsWith(".js"))   return "application/javascript";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".png"))  return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif"))  return "image/gif";
        if (name.endsWith(".svg"))  return "image/svg+xml";
        if (name.endsWith(".txt"))  return "text/plain";
        if (name.endsWith(".pdf"))  return "application/pdf";
        if (name.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }
}
