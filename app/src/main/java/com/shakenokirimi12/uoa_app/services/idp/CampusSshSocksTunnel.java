package com.shakenokirimi12.uoa_app.services.idp;

import android.util.Log;

import androidx.annotation.NonNull;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 大学の SSH ゲートウェイ (sshgate.u-aizu.ac.jp、AINS ID/PW で入れる) へ繋ぎ、その上に
 * 127.0.0.1 の SOCKS5 プロキシを立てる。ここを通した HTTP は学内発として扱われる。
 * SECIOSS のワンタイムパスワード登録は学内ネットワークからしか通らない仕様なので、登録の間だけ使う
 * (iOS 側の検証スクリプト idp_verification/test_idp_register_otp.swift が `ssh -D` でやっていたのと同じ)。
 *
 * SOCKS5 は CONNECT + 「認証なし」+ ドメイン名/IPv4 宛先だけを実装する (OkHttp が使うのはそれだけ)。
 * 各 CONNECT を SSH の direct-tcpip チャネルへ橋渡しする。
 *
 * ホスト鍵は固定 (2026-09-15 に ssh-keyscan で取得した ecdsa-sha2-nistp256。ed25519 は Android の JCA に署名実装が無く JSch が使えない)。known_hosts をユーザーに確認させる
 * UI は無いので、鍵が変わったら接続を拒否して失敗にする (パスワードを中間者へ渡さない)。
 */
public final class CampusSshSocksTunnel implements Closeable {
    private static final String TAG = "CampusSshSocksTunnel";
    public static final String GATEWAY_HOST = "sshgate.u-aizu.ac.jp";
    private static final int GATEWAY_PORT = 22;
    private static final String GATEWAY_HOST_KEY_ECDSA =
            "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBJ5iYotAQpmFeyl38gWkTZdDQm6sZjLIzTH91qrXkMFRTrbIfOOkvm2D0KTksfbsQqGwkqTcilh3A/Hx8pRWm94=";
    private static final int CONNECT_TIMEOUT_MS = 15_000;

    private final Session session;
    private final ServerSocket server;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile boolean closed;
    // 進行中のクライアントソケット。close() で明示的に閉じないと、pump() の blocking read が
    // shutdownNow() の interrupt では解けず、スレッドとソケット (fd) が漏れる。
    private final java.util.Set<Socket> activeClients =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** 接続してプロキシを立てる。失敗したら例外 (パスワード誤りも JSchException "Auth fail")。 */
    @NonNull
    public static CampusSshSocksTunnel open(@NonNull String uid, @NonNull String pass) throws JSchException, IOException {
        JSch jsch = new JSch();
        jsch.getHostKeyRepository().add(new HostKey(GATEWAY_HOST, HostKey.ECDSA256,
                Base64.getDecoder().decode(GATEWAY_HOST_KEY_ECDSA)), null);
        Session session = jsch.getSession(uid, GATEWAY_HOST, GATEWAY_PORT);
        session.setPassword(pass);
        // 固定鍵と一致しないときは接続しない。ピン留めしているのは ecdsa だけなので、サーバーが
        // 別種 (rsa/ed25519) の鍵を出してきて不一致になるのを避けるため、鍵種もそれに固定する。
        session.setConfig("StrictHostKeyChecking", "yes");
        session.setConfig("server_host_key", "ecdsa-sha2-nistp256");
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        session.setTimeout(CONNECT_TIMEOUT_MS);
        session.connect(CONNECT_TIMEOUT_MS);
        Log.d(TAG, "ssh session up");
        return new CampusSshSocksTunnel(session);
    }

    private CampusSshSocksTunnel(Session session) throws IOException {
        this.session = session;
        // ポート 0 = 空いているポートを OS に選ばせる。ループバックにしか bind しない。
        this.server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        workers.execute(this::acceptLoop);
    }

    /** OkHttpClient.Builder.proxy() に渡す。OkHttp は SOCKS 相手にはホスト名を解決せずそのまま渡す。 */
    @NonNull
    public Proxy proxy() {
        return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()));
    }

    private void acceptLoop() {
        while (!closed) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                if (!closed) Log.w(TAG, "accept failed: " + e);
                return;
            }
            Log.d(TAG, "socks accept");
            workers.execute(() -> handle(client));
        }
    }

    /** SOCKS5 の 1 接続分。プロトコルの失敗はその接続を閉じるだけで、トンネル全体は続ける。 */
    private void handle(Socket client) {
        activeClients.add(client);
        ChannelDirectTCPIP channel = null;
        try {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // greeting: VER NMETHODS METHODS...
            if (in.read() != 0x05) throw new IOException("not SOCKS5");
            int nMethods = in.read();
            boolean noAuth = false;
            for (int i = 0; i < nMethods; i++) {
                if (in.read() == 0x00) noAuth = true;
            }
            if (!noAuth) {
                out.write(new byte[]{0x05, (byte) 0xFF});
                out.flush();
                throw new IOException("client requires auth");
            }
            out.write(new byte[]{0x05, 0x00});
            out.flush();

            // request: VER CMD RSV ATYP DST.ADDR DST.PORT
            if (in.read() != 0x05) throw new IOException("bad request version");
            int cmd = in.read();
            in.read(); // RSV
            int atyp = in.read();
            String host;
            if (atyp == 0x01) {
                byte[] v4 = readFully(in, 4);
                host = InetAddress.getByAddress(v4).getHostAddress();
            } else if (atyp == 0x03) {
                int len = in.read();
                host = new String(readFully(in, len), StandardCharsets.US_ASCII);
            } else if (atyp == 0x04) {
                byte[] v6 = readFully(in, 16);
                host = InetAddress.getByAddress(v6).getHostAddress();
            } else {
                reply(out, 0x08);
                throw new IOException("unsupported ATYP " + atyp);
            }
            byte[] portBytes = readFully(in, 2);
            int port = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);
            if (cmd != 0x01) {
                reply(out, 0x07);
                throw new IOException("unsupported CMD " + cmd);
            }

            try {
                channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
            } catch (JSchException e) {
                reply(out, 0x01);
                throw new IOException("openChannel failed: " + e.getMessage());
            }
            channel.setHost(host);
            channel.setPort(port);
            // ゲートウェイから見た発信元。値自体に意味は無いが空だと拒否する実装がある。
            channel.setOrgIPAddress("127.0.0.1");
            channel.setOrgPort(client.getPort());
            // setInputStream/setOutputStream に生ソケットを渡すと、JSch が EOF 検出やフラッシュの
            // タイミングでチャネルを早期に閉じてしまい "connection closed" になる (2026-09-15 実測)。
            // getInputStream/getOutputStream を取り、両方向を自前でポンプする。
            InputStream channelIn = channel.getInputStream();
            OutputStream channelOut = channel.getOutputStream();
            try {
                channel.connect(CONNECT_TIMEOUT_MS);
            } catch (JSchException e) {
                reply(out, 0x05);
                throw new IOException("direct-tcpip to " + host + ":" + port + " failed: " + e.getMessage());
            }
            reply(out, 0x00);
            Log.d(TAG, "socks connected to " + host + ":" + port);

            // remote -> client を別スレッドで流し、client -> remote はこのスレッドで流す。
            final ChannelDirectTCPIP ch = channel;
            Thread down = new Thread(() -> pump(channelIn, out, "down"));
            down.start();
            pump(in, channelOut, "up");
            // 上り (client->remote) が終わったら遠端へ EOF を伝え、下りが終わるのを待つ。
            try {
                channelOut.close();
            } catch (IOException ignored) {
                // 既に切れている
            }
            down.join(2000);
            ch.disconnect();
        } catch (IOException | InterruptedException e) {
            Log.d(TAG, "socks connection ended: " + e.getMessage());
        } finally {
            if (channel != null) channel.disconnect();
            activeClients.remove(client);
            try {
                client.close();
            } catch (IOException ignored) {
                // 閉じる途中の失敗は無視してよい
            }
        }
    }

    /** src が尽きる (EOF) か切れるまで dst へ流す。片方向が終わったら戻る。 */
    private void pump(InputStream src, OutputStream dst, String dir) {
        byte[] buf = new byte[16384];
        try {
            int n;
            while (!closed && (n = src.read(buf)) >= 0) {
                if (n > 0) {
                    dst.write(buf, 0, n);
                    dst.flush();
                }
            }
        } catch (IOException e) {
            Log.d(TAG, "pump " + dir + " ended: " + e.getMessage());
        }
    }

    private static void reply(OutputStream out, int rep) throws IOException {
        // VER REP RSV ATYP(IPv4) BND.ADDR(0.0.0.0) BND.PORT(0)
        out.write(new byte[]{0x05, (byte) rep, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new IOException("eof");
            off += r;
        }
        return buf;
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // 閉じる途中の失敗は無視してよい
        }
        // pump() の blocking read (client ソケット) は interrupt では解けないので、明示的に閉じて
        // handle() スレッドを起こす。SSH 側 (channelIn) は session.disconnect() で解ける。
        for (Socket client : activeClients) {
            try {
                client.close();
            } catch (IOException ignored) {
                // 既に閉じている
            }
        }
        workers.shutdownNow();
        session.disconnect();
    }
}
