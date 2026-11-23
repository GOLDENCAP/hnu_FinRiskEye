package com.example.chapter07.thirdlevel;

/**
 * 过滤空白 OCR 结果和重复画面文字，减少重复请求与重复提醒。
 */
public final class TextDeduplicator {

    private String lastNormalizedText = "";

    public boolean shouldSend(String text) {
        String normalized = normalize(text);
        if (normalized.length() < 2) {
            return false;
        }
        if (normalized.equals(lastNormalizedText)) {
            return false;
        }
        lastNormalizedText = normalized;
        return true;
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replaceAll("[\\p{P}\\p{Z}\\s]+", "")
                .trim();
    }
}
