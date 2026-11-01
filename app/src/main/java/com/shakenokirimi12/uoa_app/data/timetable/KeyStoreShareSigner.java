package com.shakenokirimi12.uoa_app.data.timetable;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;

/**
 * AndroidKeyStore の EC 鍵 (secp256r1、署名だけ、SHA-256)。初めて署名が要るときに作る。
 * 利用者の認証は求めない: QR を出す・一覧を開いたときの更新で、毎回ロック解除を求めないため。
 * 鍵は AINS のユーザー名・device_id・分析用 id とは結び付けない (端末に 1 つ)。
 */
public final class KeyStoreShareSigner implements ShareSigner {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "timetable_share_signing_v3";

    @Nullable
    @Override
    public synchronized String existingPublicKey() throws GeneralSecurityException {
        Certificate cert = keyStore().getCertificate(ALIAS);
        return cert != null ? ShareCrypto.base64url(cert.getPublicKey().getEncoded()) : null;
    }

    @NonNull
    @Override
    public synchronized String publicKey() throws GeneralSecurityException {
        ensureKey();
        return ShareCrypto.base64url(keyStore().getCertificate(ALIAS).getPublicKey().getEncoded());
    }

    @NonNull
    @Override
    public synchronized byte[] sign(@NonNull byte[] data) throws GeneralSecurityException {
        ensureKey();
        KeyStore.Entry entry = keyStore().getEntry(ALIAS, null);
        if (!(entry instanceof KeyStore.PrivateKeyEntry)) throw new GeneralSecurityException("signing key missing");
        PrivateKey key = ((KeyStore.PrivateKeyEntry) entry).getPrivateKey();
        return ShareCrypto.sign(key, data);
    }

    @Nullable
    @Override
    public synchronized byte[] signIfKeyIs(@NonNull String publicKey, @NonNull byte[] data) throws GeneralSecurityException {
        if (!publicKey.equals(existingPublicKey())) return null;
        KeyStore.Entry entry = keyStore().getEntry(ALIAS, null);
        if (!(entry instanceof KeyStore.PrivateKeyEntry)) return null;
        return ShareCrypto.sign(((KeyStore.PrivateKeyEntry) entry).getPrivateKey(), data);
    }

    @Override
    public synchronized void deleteKey() throws GeneralSecurityException {
        KeyStore ks = keyStore();
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
    }

    private void ensureKey() throws GeneralSecurityException {
        if (keyStore().containsAlias(ALIAS)) return;
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
        generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build());
        generator.generateKeyPair();
    }

    @NonNull
    private static KeyStore keyStore() throws GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        try {
            ks.load(null);
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
        return ks;
    }
}
