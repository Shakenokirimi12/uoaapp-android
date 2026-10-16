package com.shakenokirimi12.uoa_app.services.idp;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketImpl;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;

import javax.net.SocketFactory;

import okhttp3.Dns;

/**
 * OkHttp の「生ソケット」を、listen したポートを経由せずに SSH のチャネル (Connector が開く) で作る。
 * 接続のたびに Connector.open(ホスト名, ポート) を呼び、その入出力を java.net.Socket として見せる。
 * TLS は OkHttp がこの上に重ねる (SSLSocketFactory.createSocket(socket, host, port, true))。
 *
 * ホスト名はゲートウェイ側で解決させたい (学内の名前は学内の DNS でしか引けないことがある) ので、
 * UNRESOLVED_DNS と組み合わせて使う。OkHttp は Dns の結果の InetAddress から接続先を作るので、
 * 名前を持った仮のアドレスを返し、connect でその名前 (getHostString) を取り出す。
 *
 * このソケットは OS のファイル記述子を持たない (SocketImpl が null)。下で上書きしていない
 * Socket のメソッドは使えない (NullPointerException になる)。
 */
final class SshChannelSocketFactory extends SocketFactory {

    /** 開いた 1 本の通り道。 */
    interface Stream {
        @NonNull InputStream input();
        @NonNull OutputStream output();
        /** 相手が送り終えた (以降 input は -1 を返す)。読み取りのタイムアウトの判定で、EOF を待ち続けないために使う。 */
        boolean isEof();
        void close();
    }

    interface Connector {
        @NonNull Stream open(@NonNull String host, int port, int timeoutMs) throws IOException;
    }

    /** 名前解決をしない。名前だけを持った仮のアドレス (0.0.0.0) を返す。 */
    static final Dns UNRESOLVED_DNS = new Dns() {
        @NonNull
        @Override
        public List<InetAddress> lookup(@NonNull String hostname) throws java.net.UnknownHostException {
            return Collections.singletonList(InetAddress.getByAddress(hostname, new byte[]{0, 0, 0, 0}));
        }
    };

    private final Connector connector;

    SshChannelSocketFactory(@NonNull Connector connector) {
        this.connector = connector;
    }

    @Override
    public Socket createSocket() throws IOException {
        return new ChannelSocket(connector);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket s = createSocket();
        s.connect(InetSocketAddress.createUnresolved(host, port));
        return s;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return createSocket(host.getHostName(), port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return createSocket(address, port);
    }

    /** Stream を Socket に見せる。 */
    static final class ChannelSocket extends Socket {
        /** 読み取りのタイムアウトがあるとき、データの到着を見に行く間隔。 */
        private static final long POLL_MS = 5;

        private final Connector connector;
        private volatile Stream stream;
        private volatile InetSocketAddress remote;
        private volatile boolean closed;
        private volatile boolean inputShutdown;
        private volatile boolean outputShutdown;
        private volatile int soTimeout;
        private InputStream in;
        private OutputStream out;

        ChannelSocket(@NonNull Connector connector) throws SocketException {
            super((SocketImpl) null);
            this.connector = connector;
        }

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            connect(endpoint, 0);
        }

        /**
         * ロックを持ったままチャネルを開かない (開くのに最大 15 秒かかる)。持ったままだと、その間に別の
         * スレッドから close() (OkHttp の Call.cancel など) を呼んだ側まで止まる。
         */
        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (closed) throw new SocketException("Socket is closed");
            if (stream != null) throw new SocketException("already connected");
            if (!(endpoint instanceof InetSocketAddress)) throw new IllegalArgumentException("unsupported address");
            InetSocketAddress target = (InetSocketAddress) endpoint;
            // 名前を持ったアドレス (UNRESOLVED_DNS) なら名前、そうでなければ IP の文字列。逆引きはしない。
            Stream s = connector.open(target.getHostString(), target.getPort(), timeout);
            remote = target;
            in = new TimeoutInputStream(s);
            out = s.output();
            stream = s;
            // 開いている間に close() されていたら、ここで閉じる (close() は stream を見てから閉じるので、
            // どちらの順で走っても少なくとも一方が閉じる。Stream.close は 2 回呼んでもよい)。
            if (closed) {
                s.close();
                throw new SocketException("Socket is closed");
            }
        }

        @Override public void bind(SocketAddress bindpoint) { /* 束ねる OS のソケットが無い */ }

        @Override
        public InputStream getInputStream() throws IOException {
            if (closed) throw new SocketException("Socket is closed");
            if (stream == null) throw new SocketException("Socket is not connected");
            if (inputShutdown) throw new SocketException("Socket input is shutdown");
            return in;
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            if (closed) throw new SocketException("Socket is closed");
            if (stream == null) throw new SocketException("Socket is not connected");
            if (outputShutdown) throw new SocketException("Socket output is shutdown");
            return out;
        }

        @Override
        public void close() {
            closed = true;
            Stream s = stream;
            if (s != null) s.close();
        }

        @Override public void shutdownInput() { inputShutdown = true; }

        @Override
        public void shutdownOutput() throws IOException {
            outputShutdown = true;
            // 相手へ EOF を伝える (チャネルの出力を閉じる)。
            if (out != null) out.close();
        }

        @Override public boolean isConnected() { return stream != null; }
        @Override public boolean isBound() { return stream != null; }
        @Override public boolean isClosed() { return closed; }
        @Override public boolean isInputShutdown() { return inputShutdown; }
        @Override public boolean isOutputShutdown() { return outputShutdown; }

        @Override public InetAddress getInetAddress() { InetSocketAddress r = remote; return r != null ? r.getAddress() : null; }
        @Override public int getPort() { InetSocketAddress r = remote; return r != null ? r.getPort() : 0; }
        @Override public SocketAddress getRemoteSocketAddress() { return remote; }
        @Override public InetAddress getLocalAddress() { return InetAddress.getLoopbackAddress(); }
        @Override public int getLocalPort() { return -1; }
        @Override public SocketAddress getLocalSocketAddress() { return null; }

        @Override public void setSoTimeout(int timeout) { soTimeout = Math.max(0, timeout); }
        @Override public int getSoTimeout() { return soTimeout; }

        // 以下は OS のソケットの調整。チャネルには当てはまらないので受け取るだけにする。
        @Override public void setTcpNoDelay(boolean on) { }
        @Override public boolean getTcpNoDelay() { return true; }
        @Override public void setSoLinger(boolean on, int linger) { }
        @Override public int getSoLinger() { return -1; }
        @Override public void setKeepAlive(boolean on) { }
        @Override public boolean getKeepAlive() { return false; }
        @Override public void setSendBufferSize(int size) { }
        @Override public int getSendBufferSize() { return 32 * 1024; }
        @Override public void setReceiveBufferSize(int size) { }
        @Override public int getReceiveBufferSize() { return 32 * 1024; }
        @Override public void setTrafficClass(int tc) { }
        @Override public int getTrafficClass() { return 0; }
        @Override public void setReuseAddress(boolean on) { }
        @Override public boolean getReuseAddress() { return false; }
        @Override public void setOOBInline(boolean on) { }
        @Override public boolean getOOBInline() { return false; }
        @Override public void sendUrgentData(int data) throws IOException { throw new SocketException("not supported"); }

        @Override
        public String toString() {
            return "ChannelSocket[" + (remote != null ? remote.getHostString() + ":" + remote.getPort() : "unconnected") + "]";
        }

        /**
         * setSoTimeout を守る入力。OkHttp は読み取りのタイムアウトと、使い回す接続の生存確認
         * (soTimeout を 1ms にして読んでみる) にこれを使う。チャネルの入力は待ち時間を指定できないので、
         * タイムアウトがあるときはデータの到着 (available) を見ながら待つ。
         */
        private final class TimeoutInputStream extends InputStream {
            private final Stream source;

            TimeoutInputStream(Stream source) { this.source = source; }

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(@NonNull byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                InputStream src = source.input();
                int timeout = soTimeout;
                if (timeout > 0) {
                    long deadline = System.nanoTime() + timeout * 1_000_000L;
                    while (src.available() <= 0 && !source.isEof()) {
                        if (closed) throw new SocketException("Socket closed");
                        if (System.nanoTime() >= deadline) throw new SocketTimeoutException("Read timed out");
                        try {
                            Thread.sleep(Math.min(POLL_MS, timeout));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new InterruptedIOException("interrupted");
                        }
                    }
                }
                return src.read(b, off, len);
            }

            @Override
            public int available() throws IOException { return source.input().available(); }

            @Override
            public void close() { ChannelSocket.this.close(); }
        }
    }
}
