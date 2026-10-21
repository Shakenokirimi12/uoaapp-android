package com.shakenokirimi12.uoa_app.services.idp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.net.ServerSocket;

/** 繋ぎ直しを待つ間は idle (メール待ち) にしない。中継の見張りが mail-timeout と取り違えないように。 */
public class ImapOtpFetcherStageTest {
    @Test
    public void connectionFailures_neverReachIdle() throws Exception {
        int port;
        // 閉じたポートに繋がせる (すぐに ECONNREFUSED になる)。
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        ImapOtpFetcher.Progress progress = new ImapOtpFetcher.Progress();
        java.util.List<ImapOtpFetcher.Progress.Stage> seen = new java.util.ArrayList<>();
        Thread watcher = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                seen.add(progress.stage());
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        });
        watcher.start();
        try {
            new ImapOtpFetcher("127.0.0.1", port, progress).fetchOtpMail("u", "p", 5, System.currentTimeMillis());
            fail("expected a connection failure");
        } catch (java.io.IOException expected) {
            // 繋ぎ直しの上限を超えて投げた。
        } finally {
            watcher.interrupt();
            watcher.join();
        }
        assertEquals(false, seen.contains(ImapOtpFetcher.Progress.Stage.IDLE));
        assertEquals(ImapOtpFetcher.Progress.Stage.CONNECT, progress.stage());
    }
}
