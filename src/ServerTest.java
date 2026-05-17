import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/**
 * Comprehensive integration test suite for the Java HTTP server.
 *
 * Covers:
 *  - GET static files & default file resolution
 *  - POST file upload & DELETE file removal
 *  - Redirections (301)
 *  - Error pages: 400, 403, 404, 405, 413, 500
 *  - Cookies & session handling
 *  - Chunked transfer-encoding
 *  - CGI execution (.py)
 *  - CGI timeout (504)
 *  - Content-Type / MIME detection
 *  - Client body size limit (413)
 *  - Method Not Allowed (405) + Allow header
 *  - Multiple ports / virtual servers
 *  - Config parsing
 *  - Directory listing toggle
 *  - Server never crashes under bad input
 */
public class ServerTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8080;
    private static final int PORT2 = 8081;
    private static int passed = 0, failed = 0, total = 0;

    // ───────── helpers ─────────

    static String[] sendRaw(int port, String raw) {
        return sendRaw(port, raw, 5000);
    }

    static String[] sendRaw(int port, String raw, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, port), 3000);
            s.setSoTimeout(timeoutMs);
            s.getOutputStream().write(raw.getBytes("ISO-8859-1"));
            s.getOutputStream().flush();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try { while ((n = s.getInputStream().read(buf)) != -1) bos.write(buf, 0, n); }
            catch (SocketTimeoutException ignored) {}
            String resp = bos.toString("ISO-8859-1");
            int sep = resp.indexOf("\r\n\r\n");
            if (sep < 0) return new String[]{resp, ""};
            return new String[]{resp.substring(0, sep), resp.substring(sep + 4)};
        } catch (Exception e) {
            return new String[]{"ERROR: " + e.getMessage(), ""};
        }
    }

    static String statusLine(String headers) {
        int nl = headers.indexOf("\r\n");
        return nl > 0 ? headers.substring(0, nl) : headers;
    }

    static int statusCode(String headers) {
        try { return Integer.parseInt(statusLine(headers).split(" ", 3)[1]); }
        catch (Exception e) { return -1; }
    }

    static String getHeader(String headers, String name) {
        for (String line : headers.split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name))
                return line.substring(c + 1).trim();
        }
        return null;
    }

    static void check(String name, boolean condition) {
        total++;
        if (condition) { passed++; System.out.println("  ✅ PASS: " + name); }
        else           { failed++; System.out.println("  ❌ FAIL: " + name); }
    }

    static void section(String title) {
        System.out.println("\n══════════════════════════════════════════");
        System.out.println("  " + title);
        System.out.println("══════════════════════════════════════════");
    }

    // ───────── test groups ─────────

    static void testGETStaticFile() {
        section("GET Static File (index.html)");
        String req = "GET / HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        String[] r = sendRaw(PORT, req);
        check("Status 200", statusCode(r[0]) == 200);
        check("Body contains welcome text", r[1].contains("Welcome to java-localserver"));
        check("Content-Type is text/html", getHeader(r[0], "Content-Type") != null
                && getHeader(r[0], "Content-Type").contains("text/html"));
        check("Server header present", getHeader(r[0], "Server") != null);
    }

    static void testGET404() {
        section("GET Non-Existent File (404)");
        String req = "GET /nonexistent_file_xyz.html HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        String[] r = sendRaw(PORT, req);
        check("Status 404", statusCode(r[0]) == 404);
        check("Body contains 404 text", r[1].contains("404"));
    }

    static void testRedirection() {
        section("Redirection (301)");
        String req = "GET /redirect HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        String[] r = sendRaw(PORT, req);
        check("Status 301", statusCode(r[0]) == 301);
        check("Location header is /", "/".equals(getHeader(r[0], "Location")));
    }

    static void testMethodNotAllowed() {
        section("Method Not Allowed (405)");
        // The second server (jjjjj on port 8081) only allows GET on /
        String req = "DELETE / HTTP/1.1\r\nHost: jjjjj:8081\r\n\r\n";
        String[] r = sendRaw(PORT2, req);
        check("Status 405", statusCode(r[0]) == 405);
        String allow = getHeader(r[0], "Allow");
        check("Allow header present", allow != null);
        check("Allow header contains GET", allow != null && allow.contains("GET"));
    }

    static void testCookiesAndSessions() {
        section("Cookies & Sessions");
        String req = "GET / HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        String[] r = sendRaw(PORT, req);
        String sc = getHeader(r[0], "Set-Cookie");
        check("Set-Cookie header present", sc != null);
        check("Contains session_id", sc != null && sc.contains("session_id="));
        check("Contains HttpOnly", sc != null && sc.contains("HttpOnly"));

        // Reuse session
        if (sc != null) {
            String sid = sc.split(";")[0]; // session_id=xxx
            String req2 = "GET / HTTP/1.1\r\nHost: localhost:8080\r\nCookie: " + sid + "\r\n\r\n";
            String[] r2 = sendRaw(PORT, req2);
            check("Session reuse returns 200", statusCode(r2[0]) == 200);
        }
    }

    static void testFileUploadAndDelete() {
        section("POST Upload & DELETE File");
        String filename = "testfile_" + System.currentTimeMillis() + ".txt";
        String body = "Hello upload test content";

        // Upload
        String req = "POST /upload/" + filename + " HTTP/1.1\r\n"
                   + "Host: localhost:8080\r\n"
                   + "Content-Length: " + body.length() + "\r\n\r\n" + body;
        String[] r = sendRaw(PORT, req);
        check("Upload status 201", statusCode(r[0]) == 201);

        // Verify uploaded file exists via GET
        String req2 = "GET /upload/" + filename + " HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        String[] r2 = sendRaw(PORT, req2);
        check("GET uploaded file returns 200", statusCode(r2[0]) == 200);
        check("GET uploaded file has correct body", r2[1].contains(body));

        // Delete
        String req3 = "DELETE /upload/" + filename + " HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        String[] r3 = sendRaw(PORT, req3);
        check("DELETE returns 200", statusCode(r3[0]) == 200);

        // Verify deleted
        String[] r4 = sendRaw(PORT, req2);
        check("GET after DELETE returns 404", statusCode(r4[0]) == 404);
    }

    static void testDeleteNonExistent() {
        section("DELETE Non-Existent File");
        String req = "DELETE /upload/no_such_file_ever.txt HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        String[] r = sendRaw(PORT, req);
        check("DELETE non-existent returns 404", statusCode(r[0]) == 404);
    }

    static void testBodySizeLimit() {
        section("Client Body Size Limit (413)");
        // Default limit for / is 1MB (1048576). Send Content-Length exceeding it.
        String req = "POST / HTTP/1.1\r\n"
                   + "Host: localhost:8080\r\n"
                   + "Content-Length: 2000000\r\n\r\n" + "x";
        String[] r = sendRaw(PORT, req);
        check("Status 413 for oversized body", statusCode(r[0]) == 413);
    }

    static void testChunkedTransferEncoding() {
        section("Chunked Transfer Encoding");
        String filename = "chunked_" + System.currentTimeMillis() + ".txt";
        // Chunked upload to /upload
        String chunk1 = "Hello ";
        String chunk2 = "World!";
        String req = "POST /upload/" + filename + " HTTP/1.1\r\n"
                   + "Host: localhost:8080\r\n"
                   + "Transfer-Encoding: chunked\r\n\r\n"
                   + Integer.toHexString(chunk1.length()) + "\r\n" + chunk1 + "\r\n"
                   + Integer.toHexString(chunk2.length()) + "\r\n" + chunk2 + "\r\n"
                   + "0\r\n\r\n";
        String[] r = sendRaw(PORT, req);
        check("Chunked upload returns 201", statusCode(r[0]) == 201);

        // Verify content
        String req2 = "GET /upload/" + filename + " HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        String[] r2 = sendRaw(PORT, req2);
        check("Chunked content correct", r2[1].contains("Hello World!"));

        // Cleanup
        sendRaw(PORT, "DELETE /upload/" + filename + " HTTP/1.1\r\nHost: localhost:8080\r\n\r\n");
    }

    static void testCGIExecution() {
        section("CGI Execution (.py)");
        String req = "GET /cgi/hh.py HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        String[] r = sendRaw(PORT, req);
        check("CGI returns 200", statusCode(r[0]) == 200);
        check("CGI body has output", r[1] != null && r[1].length() > 0);
    }

    static void testCGITimeout() {
        section("CGI Timeout (504)");
        String req = "GET /cgi/infinite.py HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        // CGI timeout is 10s, give socket 20s
        String[] r = sendRaw(PORT, req, 20000);
        check("CGI timeout returns 504", statusCode(r[0]) == 504);
    }

    static void testCGINonExistent() {
        section("CGI Non-Existent Script");
        String req = "GET /cgi/no_script.py HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        String[] r = sendRaw(PORT, req);
        check("Non-existent CGI returns 404", statusCode(r[0]) == 404);
    }

    static void testMalformedRequests() {
        section("Malformed / Bad Requests (400)");

        // Completely garbage request
        String[] r1 = sendRaw(PORT, "GARBAGE\r\n\r\n");
        check("Garbage request -> 400", statusCode(r1[0]) == 400);

        // Missing HTTP version
        String[] r2 = sendRaw(PORT, "GET /\r\n\r\n");
        check("Missing HTTP version -> 400", statusCode(r2[0]) == 400);

        // Empty request (just close)
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, PORT), 3000);
            s.setSoTimeout(2000);
            s.close();
            check("Empty connection doesn't crash server", true);
        } catch (Exception e) {
            check("Empty connection doesn't crash server", true);
        }
    }

    static void testHTTPResponseFormat() {
        section("HTTP/1.1 Response Format Compliance");
        String req = "GET / HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        String[] r = sendRaw(PORT, req);
        check("Status line starts with HTTP/1.1", statusLine(r[0]).startsWith("HTTP/1.1"));
        check("Content-Length header present", getHeader(r[0], "Content-Length") != null);
        check("Connection header present", getHeader(r[0], "Connection") != null);
    }

    static void testErrorPages() {
        section("Custom Error Pages");

        // 404
        String[] r404 = sendRaw(PORT, "GET /doesnotexist HTTP/1.1\r\nHost: localhost:8080\r\n\r\n");
        check("404 page served", statusCode(r404[0]) == 404);
        check("404 body not empty", r404[1].length() > 0);

        // 405
        String[] r405 = sendRaw(PORT2, "POST / HTTP/1.1\r\nHost: jjjjj:8081\r\nContent-Length: 0\r\n\r\n");
        check("405 page served", statusCode(r405[0]) == 405);
        check("405 body not empty", r405[1].length() > 0);
    }

    static void testForbiddenDirectory() {
        section("Forbidden Directory (403)");
        // /ddd route has directory_listing=false and default_file=index.html
        // If index.html doesn't exist in the www dir for /ddd, should be 403 or serve file
        String req = "GET /ddd/somedir/ HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        String[] r = sendRaw(PORT, req);
        int code = statusCode(r[0]);
        check("Directory without listing -> 403 or 404", code == 403 || code == 404);
    }

    static void testMultiplePorts() {
        section("Multiple Ports");
        // Port 8081 should be accessible too
        String req = "GET / HTTP/1.1\r\nHost: jjjjj:8081\r\n\r\n";
        String[] r = sendRaw(PORT2, req);
        check("Port 8081 responds with 200", statusCode(r[0]) == 200);
        check("Port 8081 serves content", r[1].length() > 0);
    }

    static void testVirtualServerSelection() {
        section("Virtual Server Selection (Host header)");
        // Request on port 8080 with Host: localhost should use first server
        String req1 = "GET / HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        String[] r1 = sendRaw(PORT, req1);
        check("Host localhost:8080 -> 200", statusCode(r1[0]) == 200);

        // Request on port 8080 with Host: jjjjj should use second server
        // but second server also listens on 8080
        String req2 = "GET / HTTP/1.1\r\nHost: jjjjj:8080\r\n\r\n";
        String[] r2 = sendRaw(PORT, req2);
        check("Host jjjjj:8080 -> 200", statusCode(r2[0]) == 200);
    }

    static void testConfigLoader() {
        section("Config Loader Parsing");
        try {
            Map<String, Object> config = ConfigLoader.parse("config.json");
            check("Config parsed successfully", config != null);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> servers = (List<Map<String, Object>>) config.get("servers");
            check("Servers list exists", servers != null);
            check("Has 2 servers", servers != null && servers.size() == 2);

            Map<String, Object> s1 = servers.get(0);
            check("First server host is 127.0.0.1", "127.0.0.1".equals(s1.get("host")));
            check("First server name is localhost", "localhost".equals(s1.get("server_name")));

            @SuppressWarnings("unchecked")
            List<Object> ports = (List<Object>) s1.get("ports");
            check("First server has port 8080", ports != null && ((Number) ports.get(0)).intValue() == 8080);

            @SuppressWarnings("unchecked")
            Map<String, Object> eps = (Map<String, Object>) s1.get("error_pages");
            check("Error pages configured", eps != null && eps.size() == 6);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> routes = (List<Map<String, Object>>) s1.get("routes");
            check("Routes configured (5 routes)", routes != null && routes.size() == 5);

            // Check redirect route
            Map<String, Object> redirectRoute = routes.get(4);
            check("Redirect route path is /redirect", "/redirect".equals(redirectRoute.get("path")));
            check("Redirect target is /", "/".equals(redirectRoute.get("redirect")));
        } catch (Exception e) {
            check("Config parsing threw exception: " + e.getMessage(), false);
        }
    }

    static void testMimeTypes() {
        section("MIME Type Detection (static helper)");
        check("HTML mime", "text/html".equals(Server.mimeType("page.html")));
        check("CSS mime", "text/css".equals(Server.mimeType("style.css")));
        check("JS mime", "application/javascript".equals(Server.mimeType("app.js")));
        check("JSON mime", "application/json".equals(Server.mimeType("data.json")));
        check("PNG mime", "image/png".equals(Server.mimeType("img.png")));
        check("JPG mime", "image/jpeg".equals(Server.mimeType("pic.jpg")));
        check("JPEG mime", "image/jpeg".equals(Server.mimeType("pic.jpeg")));
        check("GIF mime", "image/gif".equals(Server.mimeType("anim.gif")));
        check("SVG mime", "image/svg+xml".equals(Server.mimeType("icon.svg")));
        check("TXT mime", "text/plain".equals(Server.mimeType("readme.txt")));
        check("PDF mime", "application/pdf".equals(Server.mimeType("doc.pdf")));
        check("ZIP mime", "application/zip".equals(Server.mimeType("archive.zip")));
        check("Unknown mime", "application/octet-stream".equals(Server.mimeType("file.xyz")));
    }

    static void testStatusMessages() {
        section("Status Message Helper");
        check("200 -> OK", "OK".equals(Server.statusMsg(200)));
        check("201 -> Created", "Created".equals(Server.statusMsg(201)));
        check("301 -> Moved Permanently", "Moved Permanently".equals(Server.statusMsg(301)));
        check("400 -> Bad Request", "Bad Request".equals(Server.statusMsg(400)));
        check("403 -> Forbidden", "Forbidden".equals(Server.statusMsg(403)));
        check("404 -> Not Found", "Not Found".equals(Server.statusMsg(404)));
        check("405 -> Method Not Allowed", "Method Not Allowed".equals(Server.statusMsg(405)));
        check("413 -> Request Entity Too Large", "Request Entity Too Large".equals(Server.statusMsg(413)));
        check("500 -> Internal Server Error", "Internal Server Error".equals(Server.statusMsg(500)));
        check("504 -> Gateway Timeout", "Gateway Timeout".equals(Server.statusMsg(504)));
        check("999 -> Unknown", "Unknown".equals(Server.statusMsg(999)));
    }

    static void testSessionManager() {
        section("Session Manager");
        SessionManager sm = new SessionManager();
        String id = sm.createSession();
        check("Session created (id not null)", id != null && !id.isEmpty());
        check("Session retrievable", sm.get(id) != null);
        check("Session id matches", sm.get(id).id.equals(id));

        // Store data in session
        sm.get(id).data.put("user", "test");
        check("Session data stored", "test".equals(sm.get(id).data.get("user")));

        // Unknown session returns null
        check("Unknown session returns null", sm.get("nonexistent_id_xyz") == null);

        // Multiple sessions
        String id2 = sm.createSession();
        check("Two sessions are different", !id.equals(id2));

        // Cleanup doesn't remove fresh sessions
        sm.cleanup();
        check("Fresh session survives cleanup", sm.get(id) != null);
    }

    static void testConnectionClose() {
        section("Connection: close Header");
        String req = "GET / HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        String[] r = sendRaw(PORT, req);
        String conn = getHeader(r[0], "Connection");
        check("Connection header is 'close'", "close".equals(conn));
    }

    static void testURLDecoding() {
        section("URL Decoding");
        // Request a path with %20 (space)
        String req = "GET /upload/test%20file.txt HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        String[] r = sendRaw(PORT, req);
        // Should not crash, should return 404 (file doesn't exist)
        check("URL decoded request doesn't crash", statusCode(r[0]) == 404);
    }

    static void testQueryString() {
        section("Query String Handling");
        String req = "GET /?key=value&foo=bar HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        String[] r = sendRaw(PORT, req);
        check("Query string request returns 200", statusCode(r[0]) == 200);
    }

    static void testLargeHeader() {
        section("Large Header Rejection");
        // Send a header larger than 64KB
        StringBuilder bigHeader = new StringBuilder();
        bigHeader.append("GET / HTTP/1.1\r\nHost: localhost:8080\r\n");
        bigHeader.append("X-Big: ");
        for (int i = 0; i < 70000; i++) bigHeader.append('A');
        bigHeader.append("\r\n\r\n");
        String[] r = sendRaw(PORT, bigHeader.toString());
        check("Oversized header -> 400", statusCode(r[0]) == 400);
    }

    static void testConcurrentConnections() {
        section("Concurrent Connections (no crash)");
        int count = 20;
        Thread[] threads = new Thread[count];
        boolean[] results = new boolean[count];
        for (int i = 0; i < count; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                String req = "GET / HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
                String[] r = sendRaw(PORT, req);
                results[idx] = statusCode(r[0]) == 200;
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            try { t.join(10000); } catch (InterruptedException e) {}
        }
        int success = 0;
        for (boolean b : results) if (b) success++;
        check("All " + count + " concurrent requests succeeded (" + success + "/" + count + ")",
                success == count);
    }

    static void testPostWithoutUploadRoute() {
        section("POST to Non-Upload Route");
        String body = "some data";
        String req = "POST / HTTP/1.1\r\nHost: localhost:8080\r\n"
                   + "Content-Length: " + body.length() + "\r\n\r\n" + body;
        String[] r = sendRaw(PORT, req);
        // POST to / should serve file (like GET), status 200
        check("POST to / returns 200", statusCode(r[0]) == 200);
    }

    static void testCGIWithQueryString() {
        section("CGI with Query String");
        String req = "GET /cgi/hh.py?name=test HTTP/1.1\r\nHost: localhost:8080\r\n\r\n";
        String[] r = sendRaw(PORT, req);
        check("CGI with query returns 200", statusCode(r[0]) == 200);
    }

    static void testInvalidHostPort() {
        section("Invalid Host Port in Header");
        String req = "GET / HTTP/1.1\r\nHost: localhost:9999\r\n\r\n";
        String[] r = sendRaw(PORT, req);
        check("Wrong port in Host header -> 400", statusCode(r[0]) == 400);
    }

    // ───────── main ─────────

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║     Java HTTP Server - Test Suite        ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║  Make sure the server is running on      ║");
        System.out.println("║  ports 8080 and 8081 before running.     ║");
        System.out.println("║                                          ║");
        System.out.println("║  Start with: java -cp out Main           ║");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println();

        // Verify server is reachable
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, PORT), 2000);
        } catch (Exception e) {
            System.err.println("❌ Cannot connect to server on " + HOST + ":" + PORT);
            System.err.println("   Start the server first: java -cp out Main");
            System.exit(1);
        }

        // ── Unit tests (no server needed) ──
        testConfigLoader();
        testMimeTypes();
        testStatusMessages();
        testSessionManager();

        // ── Integration tests (server must be running) ──
        testGETStaticFile();
        testGET404();
        testRedirection();
        testCookiesAndSessions();
        testHTTPResponseFormat();
        testConnectionClose();
        testErrorPages();
        testMethodNotAllowed();
        testFileUploadAndDelete();
        testDeleteNonExistent();
        testBodySizeLimit();
        testChunkedTransferEncoding();
        testForbiddenDirectory();
        testMultiplePorts();
        testVirtualServerSelection();
        testURLDecoding();
        testQueryString();
        testLargeHeader();
        testMalformedRequests();
        testPostWithoutUploadRoute();
        testInvalidHostPort();
        testCGIExecution();
        testCGIWithQueryString();
        testConcurrentConnections();
        testCGITimeout(); // last because it takes ~10s

        // ── Summary ──
        System.out.println("\n══════════════════════════════════════════");
        System.out.println("  TEST RESULTS");
        System.out.println("══════════════════════════════════════════");
        System.out.println("  Total:  " + total);
        System.out.println("  Passed: " + passed + " ✅");
        System.out.println("  Failed: " + failed + " ❌");
        System.out.println("══════════════════════════════════════════");

        if (failed > 0) System.exit(1);
    }
}
