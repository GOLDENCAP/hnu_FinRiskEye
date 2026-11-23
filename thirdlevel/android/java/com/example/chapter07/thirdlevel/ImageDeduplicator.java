package com.example.chapter07.thirdlevel;

import android.graphics.Bitmap;

/**
 * 通过 64 位差值哈希判断画面是否发生了足够明显的变化。
 */
public final class ImageDeduplicator {

    private static final int HASH_WIDTH = 9;
    private static final int HASH_HEIGHT = 8;
    private static final int MAX_HAMMING_DISTANCE = 0;

    private long lastHash;
    private boolean hasHash;

    public boolean shouldAnalyze(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return false;
        }
        Bitmap small = Bitmap.createScaledBitmap(bitmap, HASH_WIDTH, HASH_HEIGHT, true);
        int[] pixels = new int[HASH_WIDTH * HASH_HEIGHT];
        small.getPixels(pixels, 0, HASH_WIDTH, 0, 0, HASH_WIDTH, HASH_HEIGHT);
        if (small != bitmap) {
            small.recycle();
        }

        long hash = 0L;
        int bit = 0;
        for (int y = 0; y < HASH_HEIGHT; y++) {
            for (int x = 0; x < HASH_WIDTH - 1; x++) {
                int left = luminance(pixels[y * HASH_WIDTH + x]);
                int right = luminance(pixels[y * HASH_WIDTH + x + 1]);
                if (left > right) {
                    hash |= (1L << bit);
                }
                bit++;
            }
        }

        if (hasHash && Long.bitCount(lastHash ^ hash) <= MAX_HAMMING_DISTANCE) {
            return false;
        }
        lastHash = hash;
        hasHash = true;
        return true;
    }

    private static int luminance(int color) {
        int r = (color >> 16) & 0xff;
        int g = (color >> 8) & 0xff;
        int b = color & 0xff;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }
}
