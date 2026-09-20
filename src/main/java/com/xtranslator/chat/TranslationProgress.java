package com.xtranslator.chat;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks and displays translation progress both in real-time Action Bar HUD and Chat.
 */
public class TranslationProgress {
    private static volatile boolean translating = false;
    private static volatile boolean cancelled = false;
    private static volatile int totalItems = 0;
    private static final AtomicInteger completedItems = new AtomicInteger(0);
    private static volatile String currentPhase = "";
    private static volatile String targetLanguage = "vi_vn";
    private static volatile int lastMilestonePercent = 0;

    public static void setTargetLanguage(String lang) {
        if (lang != null && !lang.isBlank()) {
            targetLanguage = lang.trim().toLowerCase();
        }
    }

    public static String getTargetLanguage() {
        return targetLanguage;
    }

    /**
     * Converts language code to human-readable full display name.
     */
    public static String getLanguageDisplayName(String code) {
        if (code == null || code.isBlank()) return "Không rõ";
        String clean = code.toLowerCase().trim();
        switch (clean) {
            case "vi_vn": return "Tiếng Việt";
            case "en_us": return "English (US)";
            case "en_gb": return "English (UK)";
            case "zh_cn": return "简体中文 (Chinese)";
            case "zh_tw": return "繁體中文 (Traditional Chinese)";
            case "ja_jp": return "日本語 (Japanese)";
            case "ko_kr": return "한국어 (Korean)";
            case "ru_ru": return "Русский (Russian)";
            case "fr_fr": return "Français (French)";
            case "de_de": return "Deutsch (German)";
            case "es_es": return "Español (Spanish)";
            case "pt_br": return "Português (Brasil)";
            case "th_th": return "ไทย (Thai)";
            case "id_id": return "Bahasa Indonesia";
            default: return clean.toUpperCase();
        }
    }

    /**
     * Converts language code to short display name for HUD Action Bar.
     */
    public static String getLanguageShortName(String code) {
        if (code == null || code.isBlank()) return "";
        String clean = code.toLowerCase().trim();
        switch (clean) {
            case "vi_vn": return "Tiếng Việt";
            case "en_us": return "English";
            case "zh_cn": return "中文";
            case "ja_jp": return "日本語";
            case "ko_kr": return "한국어";
            case "ru_ru": return "Русский";
            case "fr_fr": return "Français";
            case "de_de": return "Deutsch";
            case "es_es": return "Español";
            case "pt_br": return "Português";
            default: return clean.toUpperCase();
        }
    }

    /**
     * Starts tracking translation progress.
     */
    public static void startTranslation(int total) {
        startTranslation(total, "Starting...", targetLanguage);
    }

    /**
     * Starts tracking translation progress with custom phase/name.
     */
    public static void startTranslation(int total, String phase) {
        startTranslation(total, phase, targetLanguage);
    }

    /**
     * Starts tracking translation progress with custom phase/name and target language.
     */
    /**
     * Starts tracking translation progress with custom phase/name and target language.
     */
    public static void startTranslation(int total, String phase, String targetLang) {
        translating = true;
        cancelled = false;
        totalItems = Math.max(1, total);
        completedItems.set(0);
        currentPhase = phase != null ? phase : "";
        if (targetLang != null && !targetLang.isBlank()) {
            targetLanguage = targetLang.trim().toLowerCase();
        }
        lastMilestonePercent = 0;

        String langName = getLanguageDisplayName(targetLanguage);
        sendChatMessage(Component.translatable("xtranslator.chat.prefix")
                .append(Component.translatable("xtranslator.chat.start", currentPhase, langName, total)));
        updateActionBar();
    }

    /**
     * Updates the current phase.
     */
    public static void setPhase(String phase) {
        currentPhase = phase != null ? phase : "";
        updateActionBar();
    }

    /**
     * Updates progress with total completed count.
     */
    public static void updateProgress(int completed) {
        completedItems.set(completed);
        updateActionBar();
        checkMilestone();
    }

    /**
     * Adds completed delta items to progress (thread-safe).
     */
    public static void addProgress(int delta) {
        if (delta > 0) {
            completedItems.addAndGet(delta);
            updateActionBar();
            checkMilestone();
        }
    }

    /**
     * Marks translation as complete.
     */
    public static void completeTranslation() {
        if (!translating) return;
        translating = false;
        int completed = completedItems.get();
        if (!cancelled) {
            String fullLang = getLanguageDisplayName(targetLanguage);
            sendActionBar(Component.translatable("xtranslator.chat.prefix")
                    .append(Component.translatable("xtranslator.hud.complete", completed, totalItems)));
            sendChatMessage(Component.translatable("xtranslator.chat.prefix")
                    .append(Component.translatable("xtranslator.chat.complete", completed, totalItems, fullLang)));
        }
        reset();
    }

    /**
     * Cancels the current translation.
     */
    public static void cancel() {
        if (translating) {
            cancelled = true;
            translating = false;
            sendActionBar(Component.literal("§c[XTranslator] ").append(Component.translatable("xtranslator.hud.cancelled")));
            sendChatMessage(Component.literal("§c[XTranslator] ").append(Component.translatable("xtranslator.chat.cancelled")));
        }
    }

    /**
     * Checks if translation was cancelled.
     */
    public static boolean isCancelled() {
        return cancelled;
    }

    /**
     * Resets the tracker.
     */
    public static void reset() {
        translating = false;
        cancelled = false;
        totalItems = 0;
        completedItems.set(0);
        currentPhase = "";
        lastMilestonePercent = 0;
    }

    /**
     * Checks if translation is in progress.
     */
    public static boolean isTranslating() {
        return translating;
    }

    /**
     * Gets current progress percentage (0.0 to 1.0).
     */
    public static float getProgress() {
        if (totalItems == 0) return 0.0f;
        return Math.min(1.0f, (float) completedItems.get() / totalItems);
    }

    /**
     * Renders a visual colored text progress bar: [████████░░░░░░░░]
     */
    public static String getProgressBar(int current, int total, int totalBars) {
        if (total <= 0) return "";
        float ratio = Math.min(1.0f, (float) current / Math.max(1, total));
        int filled = Math.min(totalBars, Math.round(ratio * totalBars));
        StringBuilder sb = new StringBuilder("§a");
        for (int i = 0; i < filled; i++) {
            sb.append("█");
        }
        sb.append("§7");
        for (int i = filled; i < totalBars; i++) {
            sb.append("░");
        }
        return sb.toString();
    }

    /**
     * Real-time Action Bar HUD update (rendered directly above player's hotbar).
     */
    public static void updateActionBar() {
        if (!translating) return;
        int current = completedItems.get();
        int pct = totalItems > 0 ? (int) Math.min(100, ((float) current / totalItems) * 100) : 0;
        String bar = getProgressBar(current, totalItems, 10);
        String phase = currentPhase.isEmpty() ? "..." : currentPhase;
        String langShort = getLanguageShortName(targetLanguage);

        Component comp = Component.literal(
            "§b[XTranslator] §f" + phase + " §6➔ §a" + langShort + " §8[" + bar + "§8] §e" + pct + "% §7(" + current + "/" + totalItems + ")"
        );

        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.displayClientMessage(comp, true);
                }
            });
        }
    }

    /**
     * Checks if a 25% milestone has been reached to inform player in chat without spamming.
     */
    private static void checkMilestone() {
        if (!translating || totalItems <= 0) return;
        int pct = (int) (getProgress() * 100);
        if (pct >= lastMilestonePercent + 25 && pct < 100) {
            lastMilestonePercent = (pct / 25) * 25;
            String langShort = getLanguageShortName(targetLanguage);
            sendChatMessage(Component.translatable("xtranslator.chat.prefix")
                    .append(Component.translatable("xtranslator.chat.milestone", currentPhase, langShort, completedItems.get(), totalItems, pct)));
        }
    }

    /**
     * Sends a message to the player's Action Bar (overlay).
     */
    public static void sendActionBar(String message) {
        sendActionBar(Component.literal(message));
    }

    public static void sendActionBar(Component component) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.displayClientMessage(component, true);
                }
            });
        }
    }

    /**
     * Sends a message to the player's chat.
     */
    public static void sendChatMessage(String message) {
        sendChatMessage(Component.literal(message));
    }

    public static void sendChatMessage(Component component) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.sendSystemMessage(component);
                }
            });
        }
    }

    public static String getCurrentPhase() {
        return currentPhase;
    }

    public static int getCompletedItems() {
        return completedItems.get();
    }

    public static int getTotalItems() {
        return totalItems;
    }
}
