package com.shakenokirimi12.uoa_app.services;

import androidx.annotation.NonNull;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import okhttp3.Dns;

/**
 * 待ち時間に上限のある名前解決。
 *
 * InetAddress.getAllByName (OkHttp の Dns.SYSTEM と IMAP の InetSocketAddress が使う) には
 * 待ち時間の指定が無く、端末の DNS が答えないと呼んだスレッドが長く止まる。OkHttp の callTimeout も
 * 名前解決中のスレッドは止められない。別のスレッドで解決し、上限を過ぎたら UnknownHostException にする
 * (取り残したスレッドはデーモンなので、答えが返れば終わる)。
 */
public final class TimedDns implements Dns {
    /** 大学のサーバーとこのアプリの Worker の名前解決に、これ以上かかることはまず無い。 */
    public static final long DEFAULT_TIMEOUT_MS = 10_000L;

    public static final TimedDns INSTANCE = new TimedDns(DEFAULT_TIMEOUT_MS);

    private static final ExecutorService RESOLVER = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "dns-lookup");
        t.setDaemon(true);
        return t;
    });

    private final long timeoutMs;

    public TimedDns(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    @NonNull
    @Override
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        return Arrays.asList(resolve(hostname, timeoutMs));
    }

    /** host を解決する。timeoutMs を過ぎたら UnknownHostException。 */
    @NonNull
    public static InetAddress[] resolve(@NonNull String host, long timeoutMs) throws UnknownHostException {
        Future<InetAddress[]> f = RESOLVER.submit(() -> InetAddress.getAllByName(host));
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new UnknownHostException(host + ": lookup timed out after " + timeoutMs + " ms");
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new UnknownHostException(host + ": lookup interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UnknownHostException) throw (UnknownHostException) cause;
            UnknownHostException u = new UnknownHostException(host + ": " + cause);
            u.initCause(cause);
            throw u;
        }
    }
}
