package com.shakenokirimi12.uoa_app.ui.settings;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import com.shakenokirimi12.uoa_app.data.PreferenceManager;
import com.shakenokirimi12.uoa_app.services.idp.OtpAuthImport;
import com.shakenokirimi12.uoa_app.services.idp.OtpMethod;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 認証アプリにすでにある大学のアカウントのセットアップキーを、スクリーンショットの QR から取り込む。iOS の OTPKeyImportView と同じ。
 * Google 認証システムの「アカウントを移行」の QR か、1 件のセットアップキーの QR (otpauth://) を読む。
 * 鍵が IdP の登録と合っているかは、次のログインで確かめる (2 回続けて弾かれたら鍵を止めて登録し直しを案内する)。
 * 手で入力したコードとの照合はしない (同じ鍵から作るので、必ず一致して確かめにならない)。鍵も QR の中身もログに出さない。
 */
final class OtpKeyImport {
    private OtpKeyImport() {}

    /**
     * 画像に写っている QR の中身をすべて (スクリーンショットには複数写っていることがある)。読めなければ空。
     * 端末の大きな写真でも読めるよう、長い辺が 4096px を超える画像だけ縮める (スクリーンショットは原寸で読む。
     * 多くのアカウントを入れた移行の QR は細かく、半分に縮めると読めなくなる。iOS の Vision も原寸で読む)。
     */
    @NonNull
    static List<String> readQrs(@NonNull Context context, @NonNull Uri uri) {
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) return Collections.emptyList();
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) return Collections.emptyList();
                bitmap = BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap == null) return Collections.emptyList();
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int[] pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            LuminanceSource source = new RGBLuminanceSource(w, h, pixels);
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(com.google.zxing.BarcodeFormat.QR_CODE));
            Result[] results = new QRCodeMultiReader().decodeMultiple(new BinaryBitmap(new HybridBinarizer(source)), hints);
            List<String> texts = new ArrayList<>();
            for (Result r : results) if (r.getText() != null) texts.add(r.getText());
            return texts;
        } catch (NotFoundException e) {
            return Collections.emptyList();
        } catch (Exception | OutOfMemoryError e) {
            // 開けない画像・壊れた QR・縮めても大きすぎる画像 (バックグラウンドのスレッドなので、落とさずに読めない扱いにする)。
            // 中身は出さない。
            return Collections.emptyList();
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    /**
     * 鍵を保存し、方式を認証アプリ方式にする。鍵の保存に失敗したら方式も書かずに false (iOS の OTPMethodStore.commit と同じ)。
     */
    static boolean save(@NonNull PreferenceManager prefs, @NonNull OtpAuthImport.Account account, @NonNull String username) {
        if (!prefs.saveTotp(account.otpauthUrl, username)) return false;
        prefs.applyRegisteredOtpMethod(OtpMethod.AUTHENTICATOR);
        return true;
    }
}
