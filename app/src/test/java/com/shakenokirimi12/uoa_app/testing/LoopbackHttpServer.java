package com.shakenokirimi12.uoa_app.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * テスト用の最小 HTTP/1.1 サーバ (ループバックのみ、1 接続 1 リクエスト)。
 * ユニットテストのクラスパスは android.jar 由来で com.sun.net.httpserver が使えず、
 * MockWebServer も依存に無いので、ServerSocket で必要な分だけ書いている。
 */
public final class LoopbackHttpServer implements AutoCloseable {

    public static final class Recorded {
        public final String method;
        public final String path;
        public final Map<String, String> headers;
        public final String body;

        Recorded(String method, String path, Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }

        public String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
    }

    public static final class Reply {
        final int status;
        final Map<String, String> headers = new HashMap<>();
        final String body;

        public Reply(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public Reply header(String name, String value) {
            headers.put(name, value);
            return this;
        }
    }

    public interface Handler { Reply handle(Recorded request); }

    private final ServerSocket socket;
    private final Handler handler;
    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
    private final Thread thread;

    public LoopbackHttpServer(Handler handler) throws IOException {
        this.handler = handler;
        this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.thread = new Thread(this::loop, "LoopbackHttpServer");
        thread.setDaemon(true);
        thread.start();
    }

    public int port() { return socket.getLocalPort(); }

    public List<Recorded> requests() {
        synchronized (requests) { return new ArrayList<>(requests); }
    }

    /** path に届いた最初のリクエスト。無ければ null。 */
    public Recorded first(String path) {
        for (Recorded r : requests()) if (r.path.equals(path)) return r;
        return null;
    }

    private void loop() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                serve(client);
            } catch (IOException e) {
                // close() で accept が中断される。
            }
        }
    }

    private void serve(Socket client) throws IOException {
        InputStream in = new java.io.BufferedInputStream(client.getInputStream());
        String requestLine = readLine(in);
        if (requestLine == null) return;
        String[] parts = requestLine.split(" ");
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        // Content-Length はバイト数。日本語を含む本文を文字数で読むと待ち続ける。
        int length = headers.containsKey("content-length") ? Integer.parseInt(headers.get("content-length")) : 0;
        byte[] buf = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(buf, read, length - read);
            if (n < 0) break;
            read += n;
        }
        String path = parts.length > 1 ? parts[1] : "/";
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        Recorded rec = new Recorded(parts[0], path, headers, new String(buf, 0, read, StandardCharsets.UTF_8));
        requests.add(rec);

        Reply reply = handler.handle(rec);
        // 204 / 304 は本文を持てない (付けると OkHttp が ProtocolException にする)。
        boolean noBody = reply.status == 204 || reply.status == 304;
        byte[] body = reply.body != null && !noBody ? reply.body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(reply.status).append(" X\r\n");
        for (Map.Entry<String, String> h : reply.headers.entrySet()) {
            head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        head.append("Content-Length: ").append(body.length).append("\r\nConnection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        OutputStream os = client.getOutputStream();
        os.write(out.toByteArray());
        os.flush();
    }

    /** CRLF までを 1 行として読む (ヘッダは ASCII)。接続が閉じていれば null。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') line.write(b);
        }
        if (b < 0 && line.size() == 0) return null;
        return new String(line.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
