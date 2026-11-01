package com.shakenokirimi12.uoa_app.data.timetable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 時間割共有 v3 (docs/timetable-share-v3.md の 10)。サーバー (uoa-app-push) と iOS と同じフィクスチャの v3 を読む。
 * 署名の鍵はフィクスチャのテスト専用の架空の鍵 (testKey)。中身を出力しない。
 */
public class TimetableShareV3Test {
    private static JsonObject v3() {
        InputStream in = TimetableShareV3Test.class.getClassLoader().getResourceAsStream("timetable-share-v1.json");
        assertNotNull(in);
        return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("v3");
    }

    /** KeyStore の代わりにソフトウェアの鍵で署名する (JVM では AndroidKeyStore が無い)。 */
    private static final class SoftwareSigner implements ShareSigner {
        private final PrivateKey key;
        private final String publicKey;

        SoftwareSigner(JsonObject testKey) throws GeneralSecurityException {
            byte[] pkcs8 = ShareCrypto.base64urlDecode(testKey.get("privateKeyPkcs8").getAsString());
            assertNotNull(pkcs8);
            key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            publicKey = testKey.get("publicKey").getAsString();
        }

        @Override public String existingPublicKey() { return publicKey; }
        @Override public String publicKey() { return publicKey; }
        @Override public byte[] sign(byte[] data) throws GeneralSecurityException { return ShareCrypto.sign(key, data); }
        @Override public byte[] signIfKeyIs(String expected, byte[] data) throws GeneralSecurityException {
            return expected.equals(publicKey) ? sign(data) : null;
        }
        @Override public void deleteKey() {}
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    // ── 署名付きの共有 ──

    @Test
    public void fixtureShareVerifiesAndDecodesToTimetable() {
        JsonObject v3 = v3();
        SignedShare.Verified v = SignedShare.verify(v3.get("share"));
        assertNotNull(v);
        assertEquals(SharedTimetable.normalize(v3.get("timetable")), v.timetable);
        assertEquals(v3.getAsJsonObject("share").get("publicKey").getAsString(), v.share.publicKey);
        assertEquals(v3.getAsJsonObject("testKey").get("publicKey").getAsString(), v.share.publicKey);
    }

    @Test
    public void rejectsEveryInvalidShare() {
        for (JsonElement e : v3().getAsJsonArray("invalid")) {
            JsonObject item = e.getAsJsonObject();
            assertNull(item.get("why").getAsString(), SignedShare.verify(item.get("share")));
        }
    }

    @Test
    public void otherShareVerifiesWithOtherPublicKey() {
        JsonObject v3 = v3();
        SignedShare.Verified v = SignedShare.verify(v3.get("otherShare"));
        assertNotNull(v);
        assertEquals(v3.get("otherPublicKey").getAsString(), v.share.publicKey);
        assertFalse(v.share.publicKey.equals(v3.getAsJsonObject("testKey").get("publicKey").getAsString()));
    }

    @Test
    public void ownSignedShareVerifiesThroughDerToP1363() throws Exception {
        JsonObject v3 = v3();
        SoftwareSigner signer = new SoftwareSigner(v3.getAsJsonObject("testKey"));
        SharedTimetable mine = SharedTimetable.normalize(v3.get("timetable"));
        assertNotNull(mine);
        SignedShare share = SignedShare.sign(mine, signer);
        assertEquals(64, ShareCrypto.base64urlDecode(share.signature).length);
        assertFalse(share.timetable.contains("="));
        // 送る形 (JSON) を読み直して確かめる。
        SignedShare.Verified v = SignedShare.verify(JsonParser.parseString(share.toJson().toString()));
        assertNotNull(v);
        assertEquals(mine, v.timetable);
        assertEquals(3, share.toJson().get("v").getAsInt());
    }

    @Test
    public void fixtureFeedSignaturesVerifyWithSigningInputBuilder() {
        JsonObject v3 = v3();
        PublicKey key = ShareCrypto.parsePublicKey(v3.getAsJsonObject("testKey").get("publicKey").getAsString());
        assertNotNull(key);
        JsonObject feed = v3.getAsJsonObject("feed");
        String feedId = feed.get("feedId").getAsString();
        JsonObject create = feed.getAsJsonObject("create");
        assertEquals(0, create.get("version").getAsLong());
        assertTrue(ShareCrypto.verify(key, create.get("signature").getAsString(),
                ShareCrypto.feedSigningInput("create", feedId, 0, create.get("content").getAsString())));
        JsonObject put = feed.getAsJsonObject("put");
        assertTrue(ShareCrypto.verify(key, put.get("signature").getAsString(),
                ShareCrypto.feedSigningInput("put", feedId, put.get("version").getAsLong(), put.get("content").getAsString())));
        JsonObject delete = feed.getAsJsonObject("delete");
        assertTrue(ShareCrypto.verify(key, delete.get("signature").getAsString(),
                ShareCrypto.feedSigningInput("delete", feedId, delete.get("version").getAsLong(), "")));
        // 操作を取り違えた署名は通らない。
        assertFalse(ShareCrypto.verify(key, delete.get("signature").getAsString(),
                ShareCrypto.feedSigningInput("put", feedId, delete.get("version").getAsLong(), "")));

        // peer: 書き込む先 (相手のフィード) の id に、書く値 (自分のフィード) を content として署名する。
        JsonObject peer = feed.getAsJsonObject("peer");
        String target = peer.get("feedId").getAsString();
        String mine = peer.get("peerFeedId").getAsString();
        assertEquals(0, peer.get("version").getAsLong());
        assertEquals(mine, peer.get("content").getAsString());
        byte[] input = ShareCrypto.feedSigningInput("peer", target, 0, mine);
        assertArrayEquals(("uoa-timetable-feed\npeer\n" + target + "\n0\n" + mine).getBytes(StandardCharsets.UTF_8), input);
        assertTrue(ShareCrypto.verify(key, peer.get("signature").getAsString(), input));
        assertFalse(ShareCrypto.verify(key, peer.get("signature").getAsString(),
                ShareCrypto.feedSigningInput("peer", mine, 0, target)));
    }

    @Test
    public void ownFeedSignatureVerifies() throws Exception {
        JsonObject v3 = v3();
        SoftwareSigner signer = new SoftwareSigner(v3.getAsJsonObject("testKey"));
        byte[] input = ShareCrypto.feedSigningInput("put", "0123456789abcdef0123456789abcdef", 7, "abc");
        String sig = ShareCrypto.base64url(signer.sign(input));
        assertTrue(ShareCrypto.verify(ShareCrypto.parsePublicKey(signer.publicKey()), sig, input));
    }

    @Test
    public void derAndP1363ConvertBothWays() throws Exception {
        for (JsonElement e : v3().getAsJsonArray("derP1363")) {
            JsonObject item = e.getAsJsonObject();
            String why = item.get("why").getAsString();
            byte[] der = hex(item.get("der").getAsString());
            byte[] p1363 = hex(item.get("p1363").getAsString());
            assertArrayEquals(why, p1363, ShareCrypto.derToP1363(der));
            assertArrayEquals(why, der, ShareCrypto.p1363ToDer(p1363));
        }
    }

    @Test
    public void strictBase64urlAndPublicKey() {
        assertNull(ShareCrypto.base64urlDecode("YQ=="));
        assertNull(ShareCrypto.base64urlDecode("a+b/"));
        assertNull(ShareCrypto.base64urlDecode("abcde"));
        assertArrayEquals("a".getBytes(StandardCharsets.UTF_8), ShareCrypto.base64urlDecode("YQ"));
        JsonObject v3 = v3();
        assertNotNull(ShareCrypto.parsePublicKey(v3.get("otherPublicKey").getAsString()));
        assertNull(ShareCrypto.parsePublicKey("YQ"));
    }

    // ── 学期 ──

    @Test
    public void termMatchesFixture() {
        for (JsonElement e : v3().getAsJsonObject("terms").getAsJsonArray("cases")) {
            JsonObject c = e.getAsJsonObject();
            long ms = OffsetDateTime.parse(c.get("at").getAsString()).toInstant().toEpochMilli();
            assertEquals(c.get("at").getAsString(), c.get("term").getAsString(), ShareTerm.of(ms));
        }
    }

    // ── 友達の決まり ──

    private static final long OCT_2026 = OffsetDateTime.parse("2026-10-24T12:00:00+09:00").toInstant().toEpochMilli();

    private static SharedTimetable timetable(String name, String title) {
        return timetable(name, title, OCT_2026);
    }

    private static SharedTimetable timetable(String name, String title, long createdAt) {
        return SharedTimetable.create(name, createdAt, Collections.singletonList(new SharedClass(1, 1, 2, title, "")));
    }

    @Test
    public void sameKeyUpdatesInPlaceAndKeepsDisplayName() {
        List<TimetableFriend> friends = new ArrayList<>();
        TimetableShareRules.Received first = TimetableShareRules.receive(friends, timetable("太郎", "A"), "KEY", OCT_2026, "1");
        assertFalse(first.updated);
        assertFalse(first.replaced);
        TimetableShareRules.receive(friends, timetable("花子", "B"), "OTHER", OCT_2026, "2");
        // 利用者が表示名を変え、相手は止まっていたことにする。
        friends.set(1, friends.get(1).edit().name("たろう").sharingStopped(true).build());

        TimetableShareRules.Received again = TimetableShareRules.receive(friends, timetable("タロウ", "C", OCT_2026 + 60_000),
                "KEY", OCT_2026 + 1000, "3");
        assertTrue(again.updated);
        assertTrue(again.replaced);
        assertEquals(2, friends.size());
        assertEquals("1", friends.get(1).id); // 並びは変えない
        TimetableFriend f = friends.get(1);
        assertEquals("たろう", f.name);
        assertEquals("タロウ", f.sentName);
        assertEquals("C", f.timetable.classes.get(0).title);
        assertEquals(OCT_2026 + 1000, f.receivedAt);
        assertEquals("2026-2", f.term);
        assertFalse(f.sharingStopped);
    }

    @Test
    public void unsignedShareIsAlwaysNewFriend() {
        List<TimetableFriend> friends = new ArrayList<>();
        TimetableShareRules.receive(friends, timetable("太郎", "A"), null, OCT_2026, "1");
        TimetableShareRules.Received second = TimetableShareRules.receive(friends, timetable("太郎", "A"), null, OCT_2026, "2");
        assertFalse(second.updated);
        assertEquals(2, friends.size());
        assertEquals("2", friends.get(0).id);
        assertNull(friends.get(0).publicKey);
    }

    @Test
    public void replyWithFeedIdBecomesOffer() {
        TimetableFriend f = new TimetableFriend("1", "太郎", "太郎", OCT_2026, timetable("太郎", "A"));
        assertSame(f, TimetableShareRules.offer(f, null));
        TimetableFriend offered = TimetableShareRules.offer(f, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", offered.offeredFeedId);
        // 今追っているフィードの申し込みは聞き直さない。
        TimetableFriend sharing = f.edit().myFeedId("b").peerFeedId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").build();
        assertNull(TimetableShareRules.offer(sharing, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").offeredFeedId);
    }

    @Test
    public void peerFeedGoneStopsAndQueuesOwnFeed() {
        List<FeedDeletion> queue = new ArrayList<>();
        TimetableFriend sharing = new TimetableFriend("1", "太郎", "太郎", OCT_2026, timetable("太郎", "A")).edit()
                .publicKey("KEY").myFeedId("mine").myFeedKey("MK").myFeedVersion(4).peerFeedId("peer").peerFeedVersion(2L).term("2025-2")
                .build();
        assertEquals(TimetableFriend.State.SHARING, sharing.state());
        TimetableFriend stopped = TimetableShareRules.peerFeedGone(sharing, queue, "2026-2");
        assertEquals(TimetableFriend.State.STOPPED, stopped.state());
        assertTrue(stopped.sharingStopped);
        assertNull(stopped.myFeedId);
        assertNull(stopped.peerFeedId);
        assertEquals("2026-2", stopped.term);
        assertEquals(Collections.singletonList(new FeedDeletion("mine", 5, "MK")), queue);
    }

    @Test
    public void stopSharingQueuesOwnFeedAndClearsFeeds() {
        List<FeedDeletion> queue = new ArrayList<>();
        TimetableFriend waiting = new TimetableFriend("1", "太郎", "太郎", OCT_2026, timetable("太郎", "A")).edit()
                .myFeedId("mine").myFeedKey("MK").offeredFeedId("offer").build();
        assertEquals(TimetableFriend.State.WAITING, waiting.state());
        TimetableFriend stopped = TimetableShareRules.stopSharing(waiting, queue, "2026-2");
        assertEquals(TimetableFriend.State.TERM_ONLY, stopped.state());
        assertNull(stopped.offeredFeedId);
        assertEquals(Collections.singletonList(new FeedDeletion("mine", 1, "MK")), queue);
    }

    @Test
    public void termPurgeKeepsSharingFriendsOnly() {
        long spring = OffsetDateTime.parse("2026-05-01T12:00:00+09:00").toInstant().toEpochMilli();
        SharedTimetable t = timetable("x", "A");
        List<TimetableFriend> friends = new ArrayList<>();
        friends.add(new TimetableFriend("termOnly", "a", "a", spring, t));
        friends.add(new TimetableFriend("waiting", "b", "b", spring, t).edit().myFeedId("w").myFeedVersion(2).build());
        friends.add(new TimetableFriend("stopped", "c", "c", spring, t).edit().sharingStopped(true).term("2026-1").build());
        friends.add(new TimetableFriend("sharing", "d", "d", spring, t).edit().myFeedId("s").peerFeedId("p").build());
        friends.add(new TimetableFriend("current", "e", "e", OCT_2026, t));
        List<FeedDeletion> queue = new ArrayList<>();
        assertTrue(TimetableShareRules.purgeByTerm(friends, queue, "2026-2"));
        List<String> ids = new ArrayList<>();
        for (TimetableFriend f : friends) ids.add(f.id);
        assertEquals(java.util.Arrays.asList("sharing", "current"), ids);
        assertEquals(Collections.singletonList(new FeedDeletion("w", 3, null)), queue);
        assertFalse(TimetableShareRules.purgeByTerm(friends, queue, "2026-2"));
    }

    @Test
    public void friendWithoutTermIsCurrentTermUntilTheNextTermChange() {
        long spring = OffsetDateTime.parse("2026-05-01T12:00:00+09:00").toInstant().toEpochMilli();
        SharedTimetable t = timetable("x", "A");
        List<TimetableFriend> friends = new ArrayList<>();
        // 古い版で春に受け取った友達 (学期なし) と、学期を持っている友達。
        friends.add(new TimetableFriend("legacy", "a", "a", spring, t));
        friends.add(new TimetableFriend("stored", "b", "b", spring, t).edit().term("2026-1").build());
        assertTrue(TimetableShareRules.adoptCurrentTerm(friends, "2026-2"));
        assertEquals("2026-2", friends.get(0).term);
        assertEquals("2026-1", friends.get(1).term);
        assertFalse(TimetableShareRules.adoptCurrentTerm(friends, "2026-2"));

        List<FeedDeletion> queue = new ArrayList<>();
        assertTrue(TimetableShareRules.purgeByTerm(friends, queue, "2026-2"));
        assertEquals(1, friends.size());
        assertEquals("legacy", friends.get(0).id);
        // 次に学期が変わったら消える。
        assertTrue(TimetableShareRules.purgeByTerm(friends, queue, "2027-1"));
        assertTrue(friends.isEmpty());
    }

    @Test
    public void peerFeedImportsOnlyNewerVersionWithSameKey() {
        JsonObject v3 = v3();
        SignedShare.Verified feed = SignedShare.verify(v3.get("share"));
        assertNotNull(feed);
        // 持っている時間割はフィードより前に作られたもの (後のものは置き換えない。下のテスト)。
        TimetableFriend f = new TimetableFriend("1", "たろう", "太郎", OCT_2026,
                timetable("太郎", "A", feed.timetable.createdAtMillis() - 60_000)).edit()
                .publicKey(feed.share.publicKey).myFeedId("m").peerFeedId("p").build();
        TimetableFriend updated = TimetableShareRules.applyPeerFeed(f, feed, 0, OCT_2026 + 5);
        assertNotNull(updated);
        assertEquals(feed.timetable, updated.timetable);
        assertEquals("たろう", updated.name);
        assertEquals(Long.valueOf(0), updated.peerFeedVersion);
        assertNull(TimetableShareRules.applyPeerFeed(updated, feed, 0, OCT_2026 + 6));
        assertNull(TimetableShareRules.applyPeerFeed(f.edit().publicKey("OTHER").build(), feed, 1, OCT_2026));
    }

    @Test
    public void peerFeedReplacesTimetableWheneverVersionAdvances() {
        SignedShare.Verified feed = SignedShare.verify(v3().get("share"));
        assertNotNull(feed);
        long created = feed.timetable.createdAtMillis();
        for (long stored : new long[] {created, created + 60_000}) {
            SharedTimetable mine = timetable("太郎", "KEEP", stored);
            TimetableFriend f = new TimetableFriend("1", "たろう", "太郎", OCT_2026, mine).edit()
                    .publicKey(feed.share.publicKey).myFeedId("m").peerFeedId("p").peerFeedVersion(0L).build();
            // 持っている時間割の方が新しく見えても (相手の日時が進んでいたなど)、版番号が進めば置き換える。
            TimetableFriend after = TimetableShareRules.applyPeerFeed(f, feed, 3, OCT_2026 + 5);
            assertNotNull(after);
            assertEquals(Long.valueOf(3), after.peerFeedVersion);
            assertEquals(feed.timetable, after.timetable);
            assertEquals(OCT_2026 + 5, after.receivedAt);
            // 同じ版番号はもう取り込まない。
            assertNull(TimetableShareRules.applyPeerFeed(after, feed, 3, OCT_2026 + 6));
        }
    }

    @Test
    public void resetCountsOnlyRetryableDeletesAsFailures() {
        for (int status : new int[] {0, 429, 500, 503, -2}) {
            assertTrue(String.valueOf(status), TimetableShareRules.deletionFailed(status));
            assertFalse(String.valueOf(status), TimetableShareRules.dropFromQueue(status));
        }
        for (int status : new int[] {200, -1, 400, 403, 404, 409, 410}) {
            assertFalse(String.valueOf(status), TimetableShareRules.deletionFailed(status));
            assertTrue(String.valueOf(status), TimetableShareRules.dropFromQueue(status));
        }
    }

    @Test
    public void deletionQueueDropRules() {
        assertTrue(TimetableShareRules.dropFromQueue(200));
        assertTrue(TimetableShareRules.dropFromQueue(404));
        assertTrue(TimetableShareRules.dropFromQueue(410));
        // 403・409 (版番号が古い) は送り直しても通らない (積んだ削除の版番号は上がらない)。365 日の掃除で消える。
        assertTrue(TimetableShareRules.dropFromQueue(403));
        assertTrue(TimetableShareRules.dropFromQueue(409));
        assertTrue(TimetableShareRules.dropFromQueue(-1));
        assertFalse(TimetableShareRules.dropFromQueue(0));
        assertFalse(TimetableShareRules.dropFromQueue(429));
        assertFalse(TimetableShareRules.dropFromQueue(503));
        assertFalse(TimetableShareRules.dropFromQueue(-2));
    }

    @Test
    public void legacyFriendJsonStillReadsAndNewFieldsRoundTrip() {
        String legacy = "[{\"id\":\"1\",\"name\":\"たろう\",\"sentName\":\"太郎\",\"receivedAt\":" + OCT_2026
                + ",\"timetable\":" + timetable("太郎", "A").encode() + "}]";
        List<TimetableFriend> read = TimetableShareRules.readFriends(legacy);
        assertEquals(1, read.size());
        TimetableFriend f = read.get(0);
        assertNull(f.publicKey);
        assertNull(f.term);
        assertEquals("2026-2", f.effectiveTerm());
        assertEquals(TimetableFriend.State.TERM_ONLY, f.state());

        TimetableFriend full = f.edit().publicKey("K").term("2026-2").myFeedId("m").myFeedKey("MK").myFeedVersion(3)
                .myFeedContentKey("ck").peerFeedId("p").peerFeedVersion(9L).offeredFeedId("o").sharingStopped(true)
                .pendingFeedId("pend").pendingFeedKey("PK").pendingFeedVersion(0).pendingFeedOffer("o").build();
        TimetableFriend back = TimetableShareRules.readFriends(TimetableShareRules.friendsJson(Collections.singletonList(full))).get(0);
        assertEquals("K", back.publicKey);
        assertEquals("m", back.myFeedId);
        assertEquals(3, back.myFeedVersion);
        assertEquals("ck", back.myFeedContentKey);
        assertEquals("p", back.peerFeedId);
        assertEquals(Long.valueOf(9), back.peerFeedVersion);
        assertEquals("o", back.offeredFeedId);
        assertTrue(back.sharingStopped);
        assertEquals("MK", back.myFeedKey);
        assertEquals("pend", back.pendingFeedId);
        assertEquals("PK", back.pendingFeedKey);
        assertEquals(0, back.pendingFeedVersion);
        assertEquals("o", back.pendingFeedOffer);

        List<FeedDeletion> queue = java.util.Arrays.asList(new FeedDeletion("m", 4, "MK"), new FeedDeletion("n", 2, null));
        assertEquals(queue, TimetableShareRules.readDeletions(TimetableShareRules.deletionsJson(queue)));
    }

    // ── 古い共有の送り直し ──

    @Test
    public void sameKeyOlderOrEqualShareKeepsTimetableButOfferStillApplies() {
        List<TimetableFriend> friends = new ArrayList<>();
        TimetableShareRules.receive(friends, timetable("太郎", "NEW", OCT_2026 + 60_000), "KEY", OCT_2026 + 200, "1");
        for (long createdAt : new long[] {OCT_2026 + 60_000, OCT_2026}) {
            TimetableShareRules.Received r = TimetableShareRules.receive(friends,
                    timetable("太郎", "OLD", createdAt), "KEY", OCT_2026 + 9000, "x");
            assertTrue(r.updated);
            assertFalse(r.replaced); // 「更新しました」は出さない
            assertEquals(1, friends.size());
            assertEquals("NEW", friends.get(0).timetable.classes.get(0).title);
            assertEquals(OCT_2026 + 200, friends.get(0).receivedAt);
            assertSame(friends.get(0), r.friend);
            // 返事に付いた申し込みは、古い時間割でも取り込む。
            assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    TimetableShareRules.offer(r.friend, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").offeredFeedId);
        }
        // 新しいものは取り込む (createdAt は秒まで)。
        TimetableShareRules.receive(friends, timetable("太郎", "NEWER", OCT_2026 + 61_000), "KEY", OCT_2026 + 9500, "y");
        assertEquals("NEWER", friends.get(0).timetable.classes.get(0).title);
        assertEquals(OCT_2026 + 9500, friends.get(0).receivedAt);
    }

    @Test
    public void sharingFriendTakesTimetableOnlyFromPeerFeedButStillTakesOffer() {
        List<TimetableFriend> friends = new ArrayList<>();
        TimetableShareRules.receive(friends, timetable("太郎", "FEED", OCT_2026), "KEY", OCT_2026, "1");
        friends.set(0, friends.get(0).edit().myFeedId("m").myFeedKey("MINE").peerFeedId("p").peerFeedVersion(2L).build());
        TimetableShareRules.Received r = TimetableShareRules.receive(friends,
                timetable("太郎", "QR", OCT_2026 + 60_000), "KEY", OCT_2026 + 90_000, "x");
        assertTrue(r.updated);
        assertFalse(r.replaced);
        assertEquals("FEED", friends.get(0).timetable.classes.get(0).title);
        assertEquals(OCT_2026, friends.get(0).receivedAt);
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                TimetableShareRules.offer(r.friend, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb").offeredFeedId);
        // 共有していない友達は、新しい createdAt なら置き換える。
        friends.set(0, friends.get(0).edit().clearMyFeed().peerFeedId(null).peerFeedVersion(null).build());
        assertTrue(TimetableShareRules.receive(friends, timetable("太郎", "QR", OCT_2026 + 60_000), "KEY",
                OCT_2026 + 90_000, "y").replaced);
        assertEquals("QR", friends.get(0).timetable.classes.get(0).title);
    }

    @Test
    public void alreadySharingOnlyWithFeedMadeByCurrentKey() {
        TimetableFriend f = new TimetableFriend("1", "太郎", "太郎", OCT_2026, timetable("太郎", "A")).edit()
                .publicKey("THEIRS").myFeedId("m").myFeedKey("MINE").build();
        assertTrue(TimetableShareRules.alreadySharing(f, "MINE"));
        // バックアップから戻した端末 (鍵が違う・無い) では選び直させる。
        assertFalse(TimetableShareRules.alreadySharing(f, "NEW"));
        assertFalse(TimetableShareRules.alreadySharing(f, null));
        assertFalse(TimetableShareRules.alreadySharing(f.edit().clearMyFeed().build(), "MINE"));
        assertFalse(TimetableShareRules.alreadySharing(null, "MINE"));
    }

    @Test
    public void waitingFriendLinksOnlyPeerFeedSignedByTheirKey() {
        SignedShare.Verified peer = SignedShare.verify(v3().get("share"));
        assertNotNull(peer);
        TimetableFriend f = new TimetableFriend("1", "太郎", "太郎", OCT_2026, timetable("太郎", "A")).edit()
                .publicKey(peer.share.publicKey).myFeedId("m").myFeedKey("MINE").build();
        assertTrue(TimetableShareRules.peerFeedBelongsTo(f, peer));
        // ほかの人の鍵のフィード (QR を送り直した誰か)・読めないフィード・鍵の無い友達 (v2) はつながない。
        assertFalse(TimetableShareRules.peerFeedBelongsTo(f.edit().publicKey("SOMEONE_ELSE").build(), peer));
        assertFalse(TimetableShareRules.peerFeedBelongsTo(f, null));
        assertFalse(TimetableShareRules.peerFeedBelongsTo(f.edit().publicKey(null).build(), peer));
    }

    @Test
    public void mutualQrWhileWaitingLinksInsteadOfOffering() {
        SignedShare.Verified peer = SignedShare.verify(v3().get("share"));
        assertNotNull(peer);
        String fa = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        // B の側: 友達 A は承認待ち (自分のフィード FB)。A の返事が FA の申し込みを持ってきた。
        TimetableFriend waiting = new TimetableFriend("A", "A", "A", OCT_2026, timetable("A", "x")).edit()
                .publicKey(peer.share.publicKey).myFeedId("FB").myFeedKey("MINE").offeredFeedId(fa).build();
        // FB の相手の欄が FA で、FA が A の鍵のもの: つなぐ (申し込みは聞かない)。
        assertTrue(TimetableShareRules.canLinkWaiting(waiting, "FB", fa, fa, peer));
        TimetableFriend linked = TimetableShareRules.linkPeer(waiting, new ArrayList<>(), fa);
        assertEquals(TimetableFriend.State.SHARING, linked.state());
        assertEquals(fa, linked.peerFeedId);
        assertEquals("FB", linked.myFeedId);
        assertNull(linked.offeredFeedId);
        // 相手の欄がまだ空・別の値・別の鍵のフィード・もう共有中・別の自分のフィードなら、今までどおり申し込みとして聞く。
        assertFalse(TimetableShareRules.canLinkWaiting(waiting, "FB", null, fa, peer));
        assertFalse(TimetableShareRules.canLinkWaiting(waiting, "FB", "cccccccccccccccccccccccccccccccc", fa, peer));
        assertFalse(TimetableShareRules.canLinkWaiting(waiting.edit().publicKey("OTHER").build(), "FB", fa, fa, peer));
        assertFalse(TimetableShareRules.canLinkWaiting(linked, "FB", fa, fa, peer));
        assertFalse(TimetableShareRules.canLinkWaiting(waiting, "FB2", fa, fa, peer));
        // 一覧の更新 (申し込みなし) では、相手の欄の値をそのまま確かめる。
        assertTrue(TimetableShareRules.canLinkWaiting(waiting.edit().offeredFeedId(null).build(), "FB", fa, null, peer));
    }

    @Test
    public void waitingPeerGoneQueuesOwnFeedsAndBecomesTermOnly() {
        String gone = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        TimetableFriend waiting = new TimetableFriend("A", "A", "A", OCT_2026, timetable("A", "x")).edit()
                .publicKey("K").term("2026-1").myFeedId("FB").myFeedKey("MINE").myFeedVersion(2).offeredFeedId(gone)
                .pendingFeedId("PEND").pendingFeedKey("MINE").build();
        List<FeedDeletion> queue = new ArrayList<>();
        TimetableFriend after = TimetableShareRules.waitingPeerGone(waiting, queue, gone, "2026-2");
        assertEquals(TimetableFriend.State.TERM_ONLY, after.state());
        assertFalse(after.sharingStopped);
        assertNull(after.myFeedId);
        assertNull(after.pendingFeedId);
        assertNull(after.peerFeedId);
        assertNull(after.offeredFeedId);
        assertEquals("2026-2", after.term);
        assertEquals(java.util.Arrays.asList(new FeedDeletion("FB", 3, "MINE"), new FeedDeletion("PEND", 1, "MINE")), queue);
        // 別のフィードの申し込みは残す。
        assertEquals("other", TimetableShareRules.waitingPeerGone(waiting.edit().offeredFeedId("other").build(),
                new ArrayList<>(), gone, "2026-2").offeredFeedId);
    }

    // ── お互いが同時に答えたとき ──

    /** サーバーの相手の欄 (POST /feeds/:id/peer): 空か同じ値なら 200、違う値なら 409。 */
    private static int setPeer(java.util.Map<String, String> server, String target, String value) {
        String cur = server.get(target);
        if (cur != null && !cur.equals(value)) return 409;
        server.put(target, value);
        return 200;
    }

    private static TimetableFriend waitingFriend(String id, String theirKey, String myFeed, String myKey, String offered) {
        return new TimetableFriend(id, id, id, OCT_2026, timetable(id, "x")).edit().publicKey(theirKey)
                .myFeedId(myFeed).myFeedKey(myKey).offeredFeedId(offered).build();
    }

    @Test
    public void crossingAcceptsConverge() {
        java.util.Map<String, String> server = new java.util.HashMap<>();
        // A の友達 B (自分のフィード FA、B の申し込み FB) と、B の友達 A (自分のフィード FB、A の申し込み FA)。
        TimetableFriend bOnA = waitingFriend("B", "KB", "FA", "KA", "FB");
        TimetableFriend aOnB = waitingFriend("A", "KA", "FB", "KB", "FA");
        assertTrue(TimetableShareRules.canReuseMyFeed(bOnA, "KA"));
        assertTrue(TimetableShareRules.canReuseMyFeed(aOnB, "KB"));
        // 同時に答える: どちらも今の自分のフィードを書く。
        assertEquals(200, setPeer(server, bOnA.offeredFeedId, bOnA.myFeedId));
        assertEquals(200, setPeer(server, aOnB.offeredFeedId, aOnB.myFeedId));
        List<FeedDeletion> queueA = new ArrayList<>();
        List<FeedDeletion> queueB = new ArrayList<>();
        bOnA = TimetableShareRules.acceptedWithMyFeed(bOnA, queueA, "FB");
        aOnB = TimetableShareRules.acceptedWithMyFeed(aOnB, queueB, "FA");
        assertEquals(TimetableFriend.State.SHARING, bOnA.state());
        assertEquals(TimetableFriend.State.SHARING, aOnB.state());
        assertEquals(aOnB.myFeedId, bOnA.peerFeedId);
        assertEquals(bOnA.myFeedId, aOnB.peerFeedId);
        assertEquals(aOnB.myFeedId, server.get(bOnA.myFeedId));
        assertEquals(bOnA.myFeedId, server.get(aOnB.myFeedId));
        assertNull(bOnA.offeredFeedId);
        assertNull(aOnB.offeredFeedId);
        assertTrue(queueA.isEmpty());
        assertTrue(queueB.isEmpty());
    }

    @Test
    public void sequentialAcceptThenLinkConverges() {
        SignedShare.Verified aFeed = SignedShare.verify(v3().get("share"));
        assertNotNull(aFeed);
        String ka = aFeed.share.publicKey;
        java.util.Map<String, String> server = new java.util.HashMap<>();
        TimetableFriend bOnA = waitingFriend("B", "KB", "FA", ka, "FB");
        TimetableFriend aOnB = waitingFriend("A", ka, "FB", "KB", "FA");
        // A だけが答える。
        assertEquals(200, setPeer(server, "FB", "FA"));
        List<FeedDeletion> queueA = new ArrayList<>();
        bOnA = TimetableShareRules.acceptedWithMyFeed(bOnA, queueA, "FB");
        // B は一覧の更新で、自分のフィード FB の相手の欄 (FA、A の鍵) を見てつなぐ。申し込みは聞かない。
        assertTrue(TimetableShareRules.canLinkWaiting(aOnB, "FB", server.get("FB"), null, aFeed));
        aOnB = TimetableShareRules.linkPeer(aOnB, new ArrayList<>(), server.get("FB"));
        assertEquals("FB", bOnA.peerFeedId);
        assertEquals("FA", aOnB.peerFeedId);
        assertNull(aOnB.offeredFeedId);
        assertTrue(queueA.isEmpty());
        // 先に B が申し込みに答えても、同じ値で書くので 200 になり、そろう。
        assertEquals(200, setPeer(server, "FA", "FB"));
        assertEquals("FB", server.get("FA"));
    }

    @Test
    public void linkingClearsStaleOffer() {
        // 前に作りかけ P を作れたが、つなぐ応答が失われた。そのあと一覧の更新 (10.6 の 4) で FA につながる。
        TimetableFriend f = waitingFriend("A", "KA", "FB", "KB", "FA2").edit().pendingFeedId("P").pendingFeedKey("KB")
                .pendingFeedOffer("FA2").build();
        List<FeedDeletion> queue = new ArrayList<>();
        TimetableFriend linked = TimetableShareRules.linkPeer(f, queue, "FA");
        assertEquals("FA", linked.peerFeedId);
        assertEquals("FB", linked.myFeedId);
        assertNull(linked.offeredFeedId);
        assertNull(linked.pendingFeedId);
        assertNull(linked.pendingFeedKey);
        assertNull(linked.pendingFeedOffer);
        assertEquals(Collections.singletonList(new FeedDeletion("P", 1, "KB")), queue);
        // 今のフィードで答えてつながったときも同じ。
        List<FeedDeletion> queue2 = new ArrayList<>();
        assertNull(TimetableShareRules.acceptedWithMyFeed(f, queue2, "FA2").pendingFeedId);
        assertEquals(Collections.singletonList(new FeedDeletion("P", 1, "KB")), queue2);
    }

    @Test
    public void waitingOwnFeedGoneMovesTermToNow() {
        // 春に承認待ちになった友達の自分のフィードが、秋の更新で 410 だった。今期だけになり、学期は今。
        TimetableFriend waiting = waitingFriend("A", "KA", "FB", "KB", null).edit().term("2026-1").build();
        List<TimetableFriend> friends = new ArrayList<>();
        friends.add(TimetableShareRules.dropOwnFeed(waiting, "2026-2"));
        assertEquals(TimetableFriend.State.TERM_ONLY, friends.get(0).state());
        assertEquals("2026-2", friends.get(0).term);
        // 今の学期の掃除では消えず、次の学期で消える。
        List<FeedDeletion> queue = new ArrayList<>();
        assertFalse(TimetableShareRules.purgeByTerm(friends, queue, "2026-2"));
        assertEquals(1, friends.size());
        assertTrue(TimetableShareRules.purgeByTerm(friends, queue, "2027-1"));
        assertTrue(queue.isEmpty()); // 自分のフィードはもう無い
    }

    @Test
    public void acceptOfferAlreadyAnsweredWhenLinkedOrDeleted() {
        TimetableFriend waiting = waitingFriend("A", "KA", "FB", "KB", "FA");
        assertTrue(TimetableShareRules.alreadyAnswered(null));
        assertTrue(TimetableShareRules.alreadyAnswered(TimetableShareRules.linkPeer(waiting, new ArrayList<>(), "FA")));
        assertFalse(TimetableShareRules.alreadyAnswered(waiting));
        assertFalse(TimetableShareRules.alreadyAnswered(waiting.edit().offeredFeedId(null).build()));
        // 今の鍵で作っていない自分のフィード・共有中・自分のフィードなしは、今までどおり新しく作る。
        assertFalse(TimetableShareRules.canReuseMyFeed(waiting, "OTHER"));
        assertFalse(TimetableShareRules.canReuseMyFeed(waiting, null));
        assertFalse(TimetableShareRules.canReuseMyFeed(waiting.edit().clearMyFeed().build(), "KB"));
    }

    @Test
    public void sharingFriendReusesMyFeedForNewOffer() {
        // 共有中 (FB ↔ FA) に、相手が作り直したフィード FA2 の申し込みが来た。
        TimetableFriend sharing = waitingFriend("A", "KA", "FB", "KB", "FA2").edit().peerFeedId("FA").peerFeedVersion(4L).build();
        assertEquals(TimetableShareRules.AcceptRoute.MY_FEED, TimetableShareRules.acceptRoute(sharing, "KB"));
        List<FeedDeletion> queue = new ArrayList<>();
        TimetableFriend after = TimetableShareRules.acceptedWithMyFeed(sharing, queue, "FA2");
        assertEquals("FB", after.myFeedId);
        assertEquals("FA2", after.peerFeedId);
        assertNull(after.peerFeedVersion);
        assertNull(after.offeredFeedId);
        assertTrue(queue.isEmpty()); // 前の相手のフィード FA は相手のもの
    }

    @Test
    public void ownFeedGoneOnReuseFallsBackToNewFeed() {
        // FB はサーバーの掃除で消えていて、setPeer(FA, FB) が 400 だった。
        TimetableFriend waiting = waitingFriend("A", "KA", "FB", "KB", "FA").edit().term("2026-1").build();
        TimetableFriend dropped = TimetableShareRules.dropOwnFeed(waiting, "2026-2");
        assertNull(dropped.myFeedId);
        assertNull(dropped.myFeedKey);
        assertNull(dropped.peerFeedId);
        assertEquals("FA", dropped.offeredFeedId); // 同じ答えの中で答え直す
        assertEquals("2026-2", dropped.term);
        assertEquals(TimetableShareRules.AcceptRoute.NEW_FEED, TimetableShareRules.acceptRoute(dropped, "KB"));
        assertFalse(TimetableShareRules.alreadyAnswered(dropped));
    }

    @Test
    public void pendingFeedIsTriedBeforeMyFeed() {
        // 前の setPeer(FA, P) は届いたが応答が失われた。今のフィード FB で送ると 409 になるので、P から送り直す。
        java.util.Map<String, String> server = new java.util.HashMap<>();
        server.put("FA", "P");
        TimetableFriend f = waitingFriend("A", "KA", "FB", "KB", "FA").edit().pendingFeedId("P").pendingFeedKey("KB")
                .pendingFeedOffer("FA").build();
        assertEquals(TimetableShareRules.AcceptRoute.PENDING, TimetableShareRules.acceptRoute(f, "KB"));
        assertEquals(409, setPeer(server, "FA", "FB"));
        assertEquals(200, setPeer(server, "FA", "P"));
        List<FeedDeletion> queue = new ArrayList<>();
        TimetableFriend linked = TimetableShareRules.linkAccepted(f, queue, "P", "KB", "FA", null);
        assertEquals("P", linked.myFeedId);
        assertEquals("FA", linked.peerFeedId);
        assertNull(linked.pendingFeedId);
        assertNull(linked.pendingFeedOffer);
        // 前の自分のフィード FB は鍵ごと積む。
        assertEquals(Collections.singletonList(new FeedDeletion("FB", 1, "KB")), queue);

        // 別の申し込み (FA2) のための作りかけは使わず、今のフィードで答え、作りかけは鍵ごと積む。
        TimetableFriend other = f.edit().offeredFeedId("FA2").build();
        assertEquals(TimetableShareRules.AcceptRoute.MY_FEED, TimetableShareRules.acceptRoute(other, "KB"));
        List<FeedDeletion> queue2 = new ArrayList<>();
        TimetableFriend reused = TimetableShareRules.acceptedWithMyFeed(other, queue2, "FA2");
        assertEquals("FB", reused.myFeedId);
        assertNull(reused.pendingFeedId);
        assertEquals(Collections.singletonList(new FeedDeletion("P", 1, "KB")), queue2);
        // 申し込みを持たない作りかけ (開発中の版で保存した) も、同じ申し込みとは見なさない。
        assertEquals(TimetableShareRules.AcceptRoute.MY_FEED,
                TimetableShareRules.acceptRoute(f.edit().pendingFeedOffer(null).build(), "KB"));
        // 作りかけの鍵が今の鍵でなければ使わない。
        assertEquals(TimetableShareRules.AcceptRoute.NEW_FEED,
                TimetableShareRules.acceptRoute(f.edit().myFeedKey("OLD").pendingFeedKey("OLD").build(), "KB"));
    }

    @Test
    public void droppingAnOfferQueuesThePendingFeed() {
        // 共有中の友達の申し込みに答えている途中で作りかけ P ができ、最後の失敗 (または断った) で申し込みを外す。
        TimetableFriend sharing = waitingFriend("A", "KA", "FB", "KB", "FA2").edit().peerFeedId("FA")
                .pendingFeedId("P").pendingFeedKey("KB").pendingFeedOffer("FA2").build();
        List<FeedDeletion> queue = new ArrayList<>();
        TimetableFriend dropped = TimetableShareRules.dropOffer(sharing, queue);
        assertNull(dropped.offeredFeedId);
        assertNull(dropped.pendingFeedId);
        assertNull(dropped.pendingFeedKey);
        assertNull(dropped.pendingFeedOffer);
        assertEquals("FB", dropped.myFeedId);
        assertEquals("FA", dropped.peerFeedId);
        assertEquals(Collections.singletonList(new FeedDeletion("P", 1, "KB")), queue);
        // 新しく作ったフィードでつなげなかったとき: 作ったものと作りかけ (同じなら 1 回) を積む。
        List<FeedDeletion> queue2 = new ArrayList<>();
        TimetableFriend abandoned = TimetableShareRules.abandonAccept(sharing, queue2, "P", "KB", "FA2");
        assertNull(abandoned.pendingFeedId);
        assertNull(abandoned.offeredFeedId);
        assertEquals(Collections.singletonList(new FeedDeletion("P", 1, "KB")), queue2);
    }

    // ── 鍵をなくしたとき ──

    @Test
    public void lostKeyTurnsFriendsTermOnlyAndDropsOnlyThatKeysDeletions() {
        SharedTimetable t = timetable("x", "A");
        List<TimetableFriend> friends = new ArrayList<>();
        friends.add(new TimetableFriend("old", "a", "a", OCT_2026, t).edit().term("2026-1")
                .myFeedId("m1").myFeedKey("OLDKEY").peerFeedId("p1").peerFeedVersion(3L).offeredFeedId("o1").build());
        friends.add(new TimetableFriend("cur", "b", "b", OCT_2026, t).edit().term("2026-1")
                .myFeedId("m2").myFeedKey("NEWKEY").peerFeedId("p2").build());
        friends.add(new TimetableFriend("pend", "c", "c", OCT_2026, t)
                .edit().pendingFeedId("x1").pendingFeedKey("OLDKEY").offeredFeedId("o3").build());
        List<FeedDeletion> queue = new ArrayList<>(java.util.Arrays.asList(
                new FeedDeletion("d1", 2, "OLDKEY"), new FeedDeletion("d2", 2, "NEWKEY"), new FeedDeletion("d3", 1, null)));

        assertTrue(TimetableShareRules.dropLostKeyFeeds(friends, queue, "NEWKEY", "2026-2"));
        TimetableFriend old = friends.get(0);
        assertEquals(TimetableFriend.State.TERM_ONLY, old.state());
        assertNull(old.myFeedId);
        assertNull(old.myFeedKey);
        assertNull(old.peerFeedId);
        assertNull(old.peerFeedVersion);
        assertEquals("2026-2", old.term);
        assertEquals("o1", old.offeredFeedId); // 申し込みは今の鍵で答え直せる
        assertEquals(TimetableFriend.State.SHARING, friends.get(1).state());
        assertEquals("2026-1", friends.get(1).term);
        assertNull(friends.get(2).pendingFeedId);
        assertEquals("o3", friends.get(2).offeredFeedId);
        assertEquals(Collections.singletonList(new FeedDeletion("d2", 2, "NEWKEY")), queue);
        assertFalse(TimetableShareRules.dropLostKeyFeeds(friends, queue, "NEWKEY", "2026-2"));

        // 鍵が無い: どのフィードも外す。
        assertTrue(TimetableShareRules.dropLostKeyFeeds(friends, queue, null, "2026-2"));
        assertEquals(TimetableFriend.State.TERM_ONLY, friends.get(1).state());
        assertEquals("2026-2", friends.get(1).term);
        assertTrue(queue.isEmpty());
    }

    @Test
    public void legacyGlobalKeyIsAdoptedOnlyWhereMissing() {
        SharedTimetable t = timetable("x", "A");
        List<TimetableFriend> friends = new ArrayList<>();
        friends.add(new TimetableFriend("a", "a", "a", OCT_2026, t).edit().myFeedId("m1").build());
        friends.add(new TimetableFriend("b", "b", "b", OCT_2026, t).edit().myFeedId("m2").myFeedKey("K2").build());
        friends.add(new TimetableFriend("c", "c", "c", OCT_2026, t));
        List<FeedDeletion> queue = new ArrayList<>(java.util.Arrays.asList(
                new FeedDeletion("d1", 2, null), new FeedDeletion("d2", 2, "K2")));
        assertTrue(TimetableShareRules.adoptLegacyFeedKey(friends, queue, "LEGACY"));
        assertEquals("LEGACY", friends.get(0).myFeedKey);
        assertEquals("K2", friends.get(1).myFeedKey);
        assertNull(friends.get(2).myFeedKey);
        assertEquals(java.util.Arrays.asList(new FeedDeletion("d1", 2, "LEGACY"), new FeedDeletion("d2", 2, "K2")), queue);
        assertFalse(TimetableShareRules.adoptLegacyFeedKey(friends, queue, "LEGACY"));
    }

    // ── 作りかけのフィード ──

    @Test
    public void pendingFeedIsQueuedWithItsKeyOnStopAndPurge() {
        TimetableFriend f = new TimetableFriend("1", "太郎", "太郎", OCT_2026, timetable("太郎", "A")).edit()
                .myFeedId("mine").myFeedKey("MK").myFeedVersion(2).offeredFeedId("offer")
                .pendingFeedId("pend").pendingFeedKey("PK").pendingFeedVersion(0).build();
        List<FeedDeletion> queue = new ArrayList<>();
        TimetableFriend stopped = TimetableShareRules.stopSharing(f, queue, "2026-2");
        assertNull(stopped.pendingFeedId);
        assertNull(stopped.pendingFeedKey);
        assertNull(stopped.myFeedId);
        assertEquals(java.util.Arrays.asList(new FeedDeletion("mine", 3, "MK"), new FeedDeletion("pend", 1, "PK")), queue);
        // 同じフィードは二重に積まない。
        TimetableShareRules.queueOwnFeed(queue, f);
        assertEquals(2, queue.size());
    }

    @Test
    public void pendingAcceptIsReusedOnlyWithSameKeyAndLinksOrAbandons() {
        TimetableFriend f = new TimetableFriend("1", "太郎", "太郎", OCT_2026, timetable("太郎", "A")).edit()
                .myFeedId("old").myFeedKey("K").myFeedVersion(4).myFeedContentKey("ck").peerFeedId("oldpeer")
                .offeredFeedId("offer").pendingFeedId("pend").pendingFeedKey("K").pendingFeedVersion(0)
                .pendingFeedOffer("offer").build();
        assertTrue(TimetableShareRules.reusePending(f, "K"));
        assertFalse(TimetableShareRules.reusePending(f, "OTHER"));
        assertFalse(TimetableShareRules.reusePending(f.edit().clearPendingFeed().build(), "K"));
        assertFalse(TimetableShareRules.reusePending(f.edit().offeredFeedId("other").build(), "K"));

        // つながった: 前のフィードを積み、作りかけと申し込みを外す。使い回したので中身の印は無し (次の更新で PUT)。
        List<FeedDeletion> queue = new ArrayList<>();
        TimetableFriend linked = TimetableShareRules.linkAccepted(f, queue, "pend", "K", "offer", null);
        assertEquals(TimetableFriend.State.SHARING, linked.state());
        assertEquals("pend", linked.myFeedId);
        assertEquals("K", linked.myFeedKey);
        assertEquals(0, linked.myFeedVersion);
        assertNull(linked.myFeedContentKey);
        assertEquals("offer", linked.peerFeedId);
        assertNull(linked.offeredFeedId);
        assertNull(linked.pendingFeedId);
        assertEquals(Collections.singletonList(new FeedDeletion("old", 5, "K")), queue);

        // つなげない (410・409): 作ったフィードを積み、今の共有はそのまま。
        queue.clear();
        TimetableFriend abandoned = TimetableShareRules.abandonAccept(f, queue, "pend", "K", "offer");
        assertEquals("old", abandoned.myFeedId);
        assertEquals("oldpeer", abandoned.peerFeedId);
        assertNull(abandoned.offeredFeedId);
        assertNull(abandoned.pendingFeedId);
        assertEquals(Collections.singletonList(new FeedDeletion("pend", 1, "K")), queue);
    }
}
