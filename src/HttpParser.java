package src;

import java.io.ByteArrayOutputStream;

public class HttpParser {
    public static HttpRequest parse(byte[] data, ConfigLoader.AppConfig config) throws HttpError {
        int headerEnd = findHeaderEnd(data);
        if (headerEnd == -1) {
            if (data.length > config.server.maxHeaderSize) throw new HttpError(400);
            return null;
        }

        String headerText = new String(data, 0, headerEnd);
        String[] lines = headerText.split("\\r?\\n");
        if (lines.length == 0) throw new HttpError(400);

        String[] firstLine = lines[0].split("\\s+");
        if (firstLine.length != 3) throw new HttpError(400);

        HttpRequest request = new HttpRequest();
        try {
            request.setMethod(firstLine[0]);
            request.setPath(firstLine[1]);
            request.setVersion(firstLine[2]);
        } catch (IllegalArgumentException e) {
            throw new HttpError(400);
        }

        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                request.addHeader(lines[i].substring(0, colon).trim(), lines[i].substring(colon + 1).trim());
            }
        }

        byte[] body = readBody(data, headerEnd, config, request);
        if (body == null) return null;
        request.setBody(body);
        return request;
    }

    private static int findHeaderEnd(byte[] data) {
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') return i;
        }
        for (int i = 0; i < data.length - 1; i++) {
            if (data[i] == '\n' && data[i + 1] == '\n') return i;
        }
        return -1;
    }

    private static byte[] readBody(byte[] data, int headerEnd, ConfigLoader.AppConfig config, HttpRequest request) throws HttpError {
        int bodyStart = headerEnd + separatorSize(data, headerEnd);
        String transferEncoding = request.getHeaders().get("transfer-encoding");
        if (transferEncoding != null && transferEncoding.contains("chunked")) {
            return readChunkedBody(data, bodyStart, config);
        }

        String contentLength = request.getHeaders().get("content-length");
        int length = 0;
        if (contentLength != null) {
            try {
                length = Integer.parseInt(contentLength.trim());
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

    private static byte[] readChunkedBody(byte[] data, int start, ConfigLoader.AppConfig config) throws HttpError {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int pos = start;

        while (true) {
            int lineEnd = findLineEnd(data, pos);
            if (lineEnd == -1) return null;

            String line = new String(data, pos, lineEnd - pos).trim();
            int chunkSize;
            try {
                chunkSize = Integer.parseInt(line.split(";", 2)[0], 16);
            } catch (NumberFormatException e) {
                throw new HttpError(400);
            }

            pos = lineEnd + lineEndSize(data, lineEnd);
            if (chunkSize == 0) return body.toByteArray();
            if (body.size() + chunkSize > config.server.maxBodySize) throw new HttpError(413);
            if (data.length < pos + chunkSize + 2) return null;

            body.write(data, pos, chunkSize);
            pos += chunkSize;
            if (pos < data.length && data[pos] == '\r') pos++;
            if (pos < data.length && data[pos] == '\n') pos++;
        }
    }

    private static int separatorSize(byte[] data, int pos) {
        return pos + 3 < data.length && data[pos] == '\r' ? 4 : 2;
    }

    private static int findLineEnd(byte[] data, int start) {
        for (int i = start; i < data.length; i++) {
            if (data[i] == '\n') return i > 0 && data[i - 1] == '\r' ? i - 1 : i;
        }
        return -1;
    }

    private static int lineEndSize(byte[] data, int lineEnd) {
        return lineEnd + 1 < data.length && data[lineEnd] == '\r' && data[lineEnd + 1] == '\n' ? 2 : 1;
    }

    static class HttpError extends Exception {
        final int status;
        HttpError(int status) { this.status = status; }
    }
}
