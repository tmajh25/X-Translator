package com.xtranslator.translation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Protects Minecraft formatting codes, Java format specifiers,
 * and placeholders from being corrupted or altered by machine translation engines.
 */
public class FormatProtector {

    // Matches Minecraft formatting codes, Java string formats, and braced placeholders
    private static final Pattern PROTECTED_PATTERN = Pattern.compile(
        "(§[0-9a-fk-orA-FK-OR]|&[0-9a-fk-orA-FK-OR]|%(?:[0-9]+\\$)?[\\-#+ 0,(<]?[0-9]*(?:\\.[0-9]+)?[a-zA-Z%]|\\{[a-zA-Z0-9_]+\\}|<[^>]+>)"
    );

    public static class ProtectedResult {
        private final String protectedText;
        private final List<String> placeholders;

        public ProtectedResult(String protectedText, List<String> placeholders) {
            this.protectedText = protectedText;
            this.placeholders = placeholders;
        }

        public String getProtectedText() {
            return protectedText;
        }

        public List<String> getPlaceholders() {
            return placeholders;
        }

        public String restore(String translatedText) {
            if (translatedText == null || placeholders.isEmpty()) {
                return translatedText;
            }

            String result = translatedText;
            for (int i = 0; i < placeholders.size(); i++) {
                String original = placeholders.get(i);
                
                // 1. Matches ⟦P0⟧, [P0], ⟦P0 Điểm⟧, [P0 points], etc., extracting any word Google accidentally trapped inside
                Pattern tokenPattern = Pattern.compile("[⟦\\[]\\s*P" + i + "\\b([^⟧\\]]*)[⟧\\]]");
                Matcher m = tokenPattern.matcher(result);
                if (m.find()) {
                    StringBuffer sb = new StringBuffer();
                    do {
                        String inside = m.group(1) != null ? m.group(1).trim() : "";
                        String replacement = inside.isEmpty() ? original : (original + " " + inside);
                        m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                    } while (m.find());
                    m.appendTail(sb);
                    result = sb.toString();
                }

                // 2. Direct cleanups in case brackets were dropped or malformed
                result = result.replace("⟦P" + i + "⟧", original);
                result = result.replace("⟦ P" + i + " ⟧", original);
                result = result.replace("⟦ P" + i + "⟧", original);
                result = result.replace("⟦P" + i + " ⟧", original);
                result = result.replace("[P" + i + "]", original);
                result = result.replace("[ P" + i + " ]", original);
                result = result.replaceAll("⟦\\s*P" + i + "\\b", original + " ");
                result = result.replaceAll("\\bP" + i + "\\s*⟧", " " + original);
            }
            return result;
        }
    }

    /**
     * Replaces sensitive tokens with safe unicode tokens.
     */
    public static ProtectedResult protect(String input) {
        if (input == null || input.isEmpty()) {
            return new ProtectedResult(input, List.of());
        }

        Matcher matcher = PROTECTED_PATTERN.matcher(input);
        List<String> placeholders = new ArrayList<>();
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            int index = placeholders.size();
            placeholders.add(matcher.group());
            matcher.appendReplacement(sb, "⟦P" + index + "⟧");
        }
        matcher.appendTail(sb);

        return new ProtectedResult(sb.toString(), placeholders);
    }
}
