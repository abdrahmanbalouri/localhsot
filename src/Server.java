package src;

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
        client.register(selector, SelectionKey.OP_READ, new ClientState());
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientState client = (ClientState) key.attachment();
        client.lastActivity = System.currentTimeMillis();

        ByteBuffer buffer = ByteBuffer.allocate(config.server.bufferSize);
        int read = channel.read(buffer);
        if (read == -1) {
            close(key);
            return;
        }

        buffer.flip();
        while (buffer.hasRemaining()) {
            client.rawBytes.write(buffer.get());
        }

        try {
            HttpRequest request = HttpParser.parse(client.rawBytes.toByteArray(), config);
            if (request == null) return;

            HttpResponse response = router.handle(request);
            client.responseData = response.getBytes();
        } catch (HttpParser.HttpError e) {
            client.responseData = router.errorResponse(e.status).getBytes();
        }

        client.responsePos = 0;
        key.interestOps(SelectionKey.OP_WRITE);
    }

    private void write(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientState client = (ClientState) key.attachment();
        client.lastActivity = System.currentTimeMillis();

        ByteBuffer buffer = ByteBuffer.wrap(client.responseData, client.responsePos, client.responseData.length - client.responsePos);
        channel.write(buffer);
        client.responsePos += buffer.position();

        if (client.responsePos >= client.responseData.length) {
            close(key);
        }
    }

    private void closeTimedOutClients() {
        long now = System.currentTimeMillis();
        for (SelectionKey key : selector.keys()) {
            if (key.attachment() instanceof ClientState) {
                ClientState client = (ClientState) key.attachment();
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

    private static class ClientState {
        private final java.io.ByteArrayOutputStream rawBytes = new java.io.ByteArrayOutputStream();
        private byte[] responseData;
        private int responsePos;
        private long lastActivity = System.currentTimeMillis();
    }
}
