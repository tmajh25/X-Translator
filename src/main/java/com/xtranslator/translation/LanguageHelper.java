package com.xtranslator.translation;

/**
 * Helper methods for detecting languages and character sets.
 */
public class LanguageHelper {

    /**
     * Checks if a string contains Vietnamese-specific accented characters or diacritics.
     */
    public static boolean isVietnamese(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("àáảãạăắằẳẵặâấầẩẫậèéẻẽẹêếềểễệìíỉĩịòóỏõọôốồổỗộơớờởỡợùúủũụưứừửữựỳýỷỹỵđĐ".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a string contains Han (Chinese) characters.
     */
    public static boolean hasChineseCharacters(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(c);
            if (script == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a text string is already in the specified target language.
     * Prevents machine translation from overwriting existing translations.
     */
    public static boolean isAlreadyInTargetLanguage(String text, String targetLanguage) {
        if (text == null || text.isBlank()) return false;
        if (targetLanguage == null) return false;
        String lang = targetLanguage.toLowerCase().trim();

        if (lang.startsWith("vi")) {
            return isVietnamese(text);
        } else if (lang.startsWith("ru") || lang.startsWith("uk")) {
            for (int i = 0; i < text.length(); i++) {
                if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.CYRILLIC) return true;
            }
        } else if (lang.startsWith("ja")) {
            for (int i = 0; i < text.length(); i++) {
                Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
                if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) return true;
            }
        } else if (lang.startsWith("zh")) {
            return hasChineseCharacters(text);
        } else if (lang.startsWith("ko")) {
            for (int i = 0; i < text.length(); i++) {
                if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HANGUL) return true;
            }
        }
        return false;
    }
}
