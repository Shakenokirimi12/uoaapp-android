package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.NonNull;

import com.shakenokirimi12.uoa_app.testing.LoopbackHttpServer;
import com.shakenokirimi12.uoa_app.testing.LoopbackHttpServer.Reply;

import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * SSH のチャネルの代わりに、ループバックの TCP を開く Connector で確かめる。
 * 見たいのは: OkHttp がこのソケットで HTTP を話せること、ホスト名が解決されずに Connector へ渡ること、
 * setSoTimeout が守られること。
 */
public class SshChannelSocketFactoryTest {

    /** 開いた先を記録し、実際はループバックの port へ繋ぐ。 */
    private static final class LoopbackConnector implements SshChannelSocketFactory.Connector {
        final int port;
        final List<String> opened = new ArrayList<>();

        LoopbackConnector(int port) { this.port = port; }

        @NonNull
        @Override
        public SshChannelSocketFactory.Stream open(@NonNull String host, int targetPort, int timeoutMs) throws java.io.IOException {
            opened.add(host + ":" + targetPort);
            Socket s = new Socket();
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000);
            return new SshChannelSocketFactory.Stream() {
                @NonNull @Override public InputStream input() {
                    try { return s.getInputStream(); } catch (java.io.IOException e) { throw new RuntimeException(e); }
                }
                @NonNull @Override public OutputStream output() {
                    try { return s.getOutputStream(); } catch (java.io.IOException e) { throw new RuntimeException(e); }
                }
                @Override public boolean isEof() { return false; }
                @Override public void close() {
                    try { s.close(); } catch (java.io.IOException ignored) { }
                }
            };
        }
    }

    @Test
    public void okHttpTalksThroughTheChannel_andTheHostNameIsNotResolvedLocally() throws Exception {
        try (LoopbackHttpServer server = new LoopbackHttpServer(req -> new Reply(200, "on campus"))) {
            LoopbackConnector connector = new LoopbackConnector(server.port());
            OkHttpClient client = new OkHttpClient.Builder()
                    .socketFactory(new SshChannelSocketFactory(connector))
                    .dns(SshChannelSocketFactory.UNRESOLVED_DNS)
                    .build();
            // .invalid は解決できない名前。ローカルで引こうとすれば失敗する。
            Request req = new Request.Builder().url("http://intranet.invalid:8080/x").build();
            try (Response resp = client.newCall(req).execute()) {
                assertEquals(200, resp.code());
                assertEquals("on campus", resp.body().string());
            }
            assertEquals(List.of("intranet.invalid:8080"), connector.opened);
            assertEquals("intranet.invalid:8080", server.first("/x").header("Host"));
        }
    }

    @Test
    public void readHonorsSoTimeout() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            SshChannelSocketFactory factory = new SshChannelSocketFactory(new LoopbackConnector(silent.getLocalPort()));
            try (Socket s = factory.createSocket()) {
                s.connect(new InetSocketAddress(InetAddress.getByAddress("intranet.invalid", new byte[4]), 80), 1_000);
                s.setSoTimeout(100);
                long start = System.nanoTime();
                try {
                    s.getInputStream().read();
                    fail("expected a timeout");
                } catch (SocketTimeoutException expected) {
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    assertTrue("timed out after " + ms + "ms", ms >= 90 && ms < 2_000);
                }
            }
        }
    }
}
