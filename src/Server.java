package src;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class Server {
    private final ConfigLoader.AppConfig config;
    private final Router router;
    private final Selector selector;

    public Server(ConfigLoader.AppConfig config) throws IOException {
        this.config = config;
        this.router = new Router(config);
        this.selector = Selector.open();
        openPorts();
    }

    public void start() throws IOException {
        while (true) {
            selector.select(100);
            closeTimedOutClients();

            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();
                if (!key.isValid()) continue;

                try {
                    if (key.isAcceptable()) accept(key);
                    if (key.isReadable()) read(key);
                    if (key.isWritable()) write(key);
                } catch (Exception e) {
                    close(key);
                }
            }
        }
    }

    private void openPorts() throws IOException {
        Set<Integer> ports = new HashSet<>(config.server.ports);
        if (ports.isEmpty()) throw new IOException("No ports configured");

        for (int port : ports) {
            ServerSocketChannel server = ServerSocketChannel.open();
            server.configureBlocking(false);
            server.bind(new InetSocketAddress("0.0.0.0", port));
            server.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("Server listening on port " + port);
        }
    }

    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        if (client == null) return;

        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ, new Client());
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Client client = (Client) key.attachment();
        client.lastActivity = System.currentTimeMillis();

        int read = channel.read(client.buffer);
        if (read == -1) {
            close(key);
            return;
        }

        client.buffer.flip();
        while (client.buffer.hasRemaining()) {
            client.requestBytes.write(client.buffer.get());
        }
        client.buffer.clear();

        try {
            HttpRequest request = parseRequest(client.requestBytes.toByteArray());
            if (request == null) return;

            HttpResponse response = router.handle(request);
            client.response = ByteBuffer.wrap(response.getBytes());
            key.interestOps(SelectionKey.OP_WRITE);
        } catch (HttpError e) {
            client.response = ByteBuffer.wrap(router.errorResponse(e.status).getBytes());
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private void write(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Client client = (Client) key.attachment();
        client.lastActivity = System.currentTimeMillis();

        channel.write(client.response);
        if (!client.response.hasRemaining()) {
            close(key);
        }
    }

    private HttpRequest parseRequest(byte[] data) throws HttpError {
        int headerEnd = headerEnd(data);
        if (headerEnd == -1) {
            if (data.length > config.server.maxHeaderSize) throw new HttpError(400);
            return null;
        }

        String headersText = new String(data, 0, headerEnd);
        String[] lines = headersText.split("\\r?\\n");
        if (lines.length == 0) throw new HttpError(400);

        String[] first = lines[0].split("\\s+");
        if (first.length != 3) throw new HttpError(400);

        HttpRequest request = new HttpRequest();
        try {
            request.setMethod(first[0]);
            request.setPath(first[1]);
            request.setVersion(first[2]);
        } catch (IllegalArgumentException e) {
            throw new HttpError(400);
        }

        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                request.addHeader(lines[i].substring(0, colon), lines[i].substring(colon + 1));
            }
        }

        int bodyStart = headerEnd + separatorSize(data, headerEnd);
        byte[] body = body(data, bodyStart, request);
        if (body == null) return null;
        request.setBody(body);
        return request;
    }

    private byte[] body(byte[] data, int bodyStart, HttpRequest request) throws HttpError {
        String transferEncoding = request.getHeaders().get("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
            return chunkedBody(data, bodyStart);
        }

        int length = 0;
        String contentLength = request.getHeaders().get("content-length");
        if (contentLength != null) {
            try {
                length = Integer.parseInt(contentLength);
            } catch (NumberFormatException e) {
                throw new HttpError(400);
            }
        }

        if (length > config.server.maxBodySize) throw new HttpError(413);
        if (data.length - bodyStart < length) return null;

        byte[] body = new byte[length];
        System.arraycopy(data, bodyStart, body, 0, length);
        return body;
    }

    private byte[] chunkedBody(byte[] data, int start) throws HttpError {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int pos = start;

        while (true) {
            int lineEnd = findLineEnd(data, pos);
            if (lineEnd == -1) return null;

            String line = new String(data, pos, lineEnd - pos).trim();
            int size;
            try {
                size = Integer.parseInt(line.split(";", 2)[0], 16);
            } catch (NumberFormatException e) {
                throw new HttpError(400);
            }

            pos = lineEnd + lineSeparatorSize(data, lineEnd);
            if (size == 0) return body.toByteArray();
            if (body.size() + size > config.server.maxBodySize) throw new HttpError(413);
            if (data.length < pos + size + 2) return null;

            body.write(data, pos, size);
            pos += size;
            if (pos < data.length && data[pos] == '\r') pos++;
            if (pos < data.length && data[pos] == '\n') pos++;
        }
    }

    private int headerEnd(byte[] data) {
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') return i;
        }
        for (int i = 0; i < data.length - 1; i++) {
            if (data[i] == '\n' && data[i + 1] == '\n') return i;
        }
        return -1;
    }

    private int separatorSize(byte[] data, int pos) {
        return data[pos] == '\r' ? 4 : 2;
    }

    private int findLineEnd(byte[] data, int start) {
        for (int i = start; i < data.length; i++) {
            if (data[i] == '\n') return data[i - 1] == '\r' ? i - 1 : i;
        }
        return -1;
    }

    private int lineSeparatorSize(byte[] data, int lineEnd) {
        return lineEnd + 1 < data.length && data[lineEnd] == '\r' && data[lineEnd + 1] == '\n' ? 2 : 1;
    }

    private void closeTimedOutClients() {
        long now = System.currentTimeMillis();
        for (SelectionKey key : selector.keys()) {
            if (key.attachment() instanceof Client) {
                Client client = (Client) key.attachment();
                if (now - client.lastActivity > config.server.keepAliveTimeoutMs) {
                    close(key);
                }
            }
        }
    }

    private void close(SelectionKey key) {
        try {
            key.channel().close();
        } catch (IOException ignored) {
        }
        key.cancel();
    }

    private static class Client {
        private final ByteBuffer buffer = ByteBuffer.allocate(8192);
        private final ByteArrayOutputStream requestBytes = new ByteArrayOutputStream();
        private ByteBuffer response;
        private long lastActivity = System.currentTimeMillis();
    }

    private static class HttpError extends Exception {
        private final int status;

        private HttpError(int status) {
            this.status = status;
        }
    }
}
