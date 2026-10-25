package com.shakenokirimi12.uoa_app.ui.timetable;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** 文字列の QR コード (誤り訂正 M、iOS と同じ)。点をぼかさないよう、補間せずに sizePx まで拡大する。 */
public final class QrCodes {
    private QrCodes() {}

    @Nullable
    public static Bitmap make(@NonNull String text, int sizePx) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) pixels[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            Bitmap small = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
            int scale = Math.max(1, sizePx / w);
            return Bitmap.createScaledBitmap(small, w * scale, h * scale, false);
        } catch (WriterException | IllegalArgumentException e) {
            return null;
        }
    }
}
