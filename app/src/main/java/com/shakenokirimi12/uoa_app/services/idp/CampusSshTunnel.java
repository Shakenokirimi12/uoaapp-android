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
import java.net.ConnectException;
import java.net.Proxy;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

/**
 * 大学の SSH ゲートウェイ (sshgate.u-aizu.ac.jp、AINS ID/PW で入れる) へ繋ぎ、OkHttp の接続を
 * SSH の direct-tcpip チャネルで張る。ここを通した HTTP は学内発として扱われる。
 * SECIOSS のワンタイムパスワード登録は学内ネットワークからしか通らない仕様なので、登録の間だけ使う
 * (iOS 側の検証スクリプト idp_verification/test_idp_register_otp.swift が `ssh -D` でやっていたのと同じ)。
 *
 * どこにも listen しない。以前は 127.0.0.1 に認証なしの SOCKS5 を立てていて、登録の間は端末内の
 * 他のアプリもそこから利用者の SSH セッションで学内へ入れた (CLAUDE-SECURITY F12)。今は
 * OkHttp の SocketFactory がチャネルを直接開くので、このプロセスの外からは使えない。
 *
 * ホスト鍵は固定 (2026-09-15 に ssh-keyscan で取得した ecdsa-sha2-nistp256。ed25519 は Android の JCA に署名実装が無く JSch が使えない)。known_hosts をユーザーに確認させる
 * UI は無いので、鍵が変わったら接続を拒否して失敗にする (パスワードを中間者へ渡さない)。
 */
public final class CampusSshTunnel implements Closeable {
    private static final String TAG = "CampusSshTunnel";
    public static final String GATEWAY_HOST = "sshgate.u-aizu.ac.jp";
    private static final int GATEWAY_PORT = 22;
    private static final String GATEWAY_HOST_KEY_ECDSA =
            "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBJ5iYotAQpmFeyl38gWkTZdDQm6sZjLIzTH91qrXkMFRTrbIfOOkvm2D0KTksfbsQqGwkqTcilh3A/Hx8pRWm94=";
    private static final int CONNECT_TIMEOUT_MS = 15_000;

    private final Session session;
    private final SshChannelSocketFactory socketFactory;
    /** このトンネル専用。閉じるときに中の接続を全部捨てる (直接経路の接続と混ぜない)。 */
    private final ConnectionPool pool = new ConnectionPool(4, 1, TimeUnit.MINUTES);
    private final AtomicInteger originPort = new AtomicInteger(40000);

    /** 接続する。失敗したら例外 (パスワード誤りも JSchException "Auth fail")。 */
    @NonNull
    public static CampusSshTunnel open(@NonNull String uid, @NonNull String pass) throws JSchException {
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
        return new CampusSshTunnel(session);
    }

    private CampusSshTunnel(Session session) {
        this.session = session;
        this.socketFactory = new SshChannelSocketFactory(this::openChannel);
    }

    /**
     * base の設定 (リダイレクトを追わない等) のまま、接続だけをこのトンネルに通すクライアントにする。
     * 名前解決はゲートウェイ側 (学内の DNS) に任せる (SshChannelSocketFactory.UNRESOLVED_DNS)。
     * HTTP/1.1 に限る: 名前を解決しないので全ホストが同じ仮のアドレスになり、HTTP/2 の接続の相乗り
     * (coalescing) の判定に使うアドレスが意味を失うため。
     */
    @NonNull
    public OkHttpClient configure(@NonNull OkHttpClient base) {
        return base.newBuilder()
                .proxy(Proxy.NO_PROXY)
                .socketFactory(socketFactory)
                .dns(SshChannelSocketFactory.UNRESOLVED_DNS)
                .connectionPool(pool)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .build();
    }

    /** 1 接続分の direct-tcpip チャネルを開く (SshChannelSocketFactory から、接続のたびに)。 */
    private SshChannelSocketFactory.Stream openChannel(String host, int port, int timeoutMs) throws IOException {
        if (!session.isConnected()) throw new ConnectException("ssh session closed");
        ChannelDirectTCPIP channel;
        try {
            channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
        } catch (JSchException e) {
            throw new ConnectException("openChannel failed: " + e.getMessage());
        }
        channel.setHost(host);
        channel.setPort(port);
        // ゲートウェイから見た発信元。値自体に意味は無いが空だと拒否する実装がある。
        channel.setOrgIPAddress("127.0.0.1");
        channel.setOrgPort(originPort.getAndIncrement());
        // setInputStream/setOutputStream に生ソケットを渡すと、JSch が EOF 検出やフラッシュの
        // タイミングでチャネルを早期に閉じてしまい "connection closed" になる (2026-09-15 実測)。
        // getInputStream/getOutputStream を connect の前に取る。
        InputStream in = channel.getInputStream();
        OutputStream out = channel.getOutputStream();
        try {
            channel.connect(timeoutMs > 0 ? timeoutMs : CONNECT_TIMEOUT_MS);
        } catch (JSchException e) {
            channel.disconnect();
            throw new ConnectException("direct-tcpip to " + host + ":" + port + " failed: " + e.getMessage());
        }
        Log.d(TAG, "channel open to " + host + ":" + port);
        return new SshChannelSocketFactory.Stream() {
            @NonNull @Override public InputStream input() { return in; }
            @NonNull @Override public OutputStream output() { return out; }
            @Override public boolean isEof() { return channel.isEOF() || channel.isClosed(); }
            @Override public void close() { channel.disconnect(); }
        };
    }

    @Override
    public void close() {
        // チャネルは session.disconnect() で全部閉じ、読み待ちのスレッドも起きる。
        pool.evictAll();
        session.disconnect();
    }
}
