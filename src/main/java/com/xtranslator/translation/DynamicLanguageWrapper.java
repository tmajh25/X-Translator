package com.xtranslator.translation;

import com.xtranslator.XTranslationManager;
import com.xtranslator.XTranslatorMod;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Dynamic Language Interceptor:
 * Intercepts Minecraft's Language.getInstance() to dynamically translate any translatable
 * text component and literal screen text on the fly without requiring a resource pack reload (F3 + T).
 */
public class DynamicLanguageWrapper extends Language {
    private final Language delegate;

    private static final Set<String> PENDING_KEYS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> FAILED_KEYS = new ConcurrentHashMap<>();

    private static boolean isFailedRecently(String key) {
        Long time = FAILED_KEYS.get(key);
        if (time == null) return false;
        if (System.currentTimeMillis() - time > 180_000) { // 3 minutes cooldown
            FAILED_KEYS.remove(key);
            return false;
        }
        return true;
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "XTranslator-DynamicLang");
        t.setDaemon(true);
        return t;
    });

    private static final Pattern LITERAL_FILTER = Pattern.compile(".*[a-zA-Z]{3,}.*");

    public DynamicLanguageWrapper(Language delegate) {
        this.delegate = delegate;
    }

    public Language getDelegate() {
        return delegate;
    }

    @Override
    public String getOrDefault(String key) {
        String defaultVal = delegate.getOrDefault(key);
        return resolveTranslation(key, defaultVal);
    }

    @Override
    public String getOrDefault(String key, String fallback) {
        String defaultVal = delegate.getOrDefault(key, fallback);
        return resolveTranslation(key, defaultVal);
    }

    private String resolveTranslation(String key, String defaultVal) {
        if (key == null || key.isEmpty()) {
            return defaultVal;
        }

        XTranslationManager manager;
        try {
            manager = XTranslationManager.getInstance();
        } catch (Exception e) {
            return defaultVal;
        }

        // 0. If key is already translated in ANY active pack/mod, NEVER override!
        if (manager.isAlreadyTranslated(key)) {
            return defaultVal;
        }

        String targetLang = manager.getTargetLanguage();
        // If defaultVal is already in the target language (e.g. Vietnamese), NEVER override!
        if (LanguageHelper.isAlreadyInTargetLanguage(defaultVal, targetLang)) {
            return defaultVal;
        }

        TranslationService service = manager.getTranslationService();
        if (service == null) {
            return defaultVal;
        }

        // 1. Check live memory cache
        Map<String, String> cache = service.getTranslationCache();
        String cached = cache.get(key);
        if (cached != null) {
            if (targetLang.startsWith("vi") && LanguageHelper.hasChineseCharacters(cached)) {
                // Reject leaked Chinese text in cache
            } else {
                return cached;
            }
        }

        // 2. Check if this key should be translated dynamically
        if (shouldTranslateKey(manager, key, defaultVal)) {
            if (!PENDING_KEYS.contains(key) && !isFailedRecently(key)) {
                PENDING_KEYS.add(key);

                String sourceText = manager.getSourceTextFor(key);
                if (sourceText == null || sourceText.isBlank() || sourceText.equals(key)) {
                    sourceText = (defaultVal != null && !defaultVal.equals(key)) ? defaultVal : null;
                }

                // If source text is already target language, don't translate
                if (LanguageHelper.isAlreadyInTargetLanguage(sourceText, targetLang)) {
                    FAILED_KEYS.put(key, System.currentTimeMillis());
                    PENDING_KEYS.remove(key);
                    return defaultVal;
                }

                final String textToTranslate = sourceText;
                if (textToTranslate != null && !textToTranslate.isBlank() && !textToTranslate.equals(key)) {
                    EXECUTOR.submit(() -> {
                        try {
                            String translated = service.translate(key, textToTranslate);
                            if (translated != null && !translated.isBlank() && !translated.equals(textToTranslate)) {
                                if (targetLang.startsWith("vi") && LanguageHelper.hasChineseCharacters(translated)) {
                                    FAILED_KEYS.put(key, System.currentTimeMillis());
                                    return;
                                }
                                manager.addPriorityTranslation(key, translated);
                            } else {
                                FAILED_KEYS.put(key, System.currentTimeMillis());
                            }
                        } catch (Exception ex) {
                            FAILED_KEYS.put(key, System.currentTimeMillis());
                            XTranslatorMod.LOGGER.debug("Dynamic translation failed for {}: {}", key, ex.getMessage());
                        } finally {
                            PENDING_KEYS.remove(key);
                        }
                    });
                } else {
                    FAILED_KEYS.put(key, System.currentTimeMillis());
                    PENDING_KEYS.remove(key);
                }
            }
        }

        return defaultVal;
    }

    private boolean shouldTranslateKey(XTranslationManager manager, String key, String text) {
        if (manager.isUntranslatedKey(key)) {
            return true;
        }

        // Ignore internal or technical keys
        if (key.startsWith("key.") || key.startsWith("subtitles.") || key.startsWith("soundCategory.") || key.startsWith("narrator.")) {
            return false;
        }

        // Mod translatable keys with dots
        if (key.contains(".") && !key.startsWith("minecraft.")) {
            return true;
        }

        return false;
    }

    @Override
    public boolean has(String key) {
        try {
            XTranslationManager manager = XTranslationManager.getInstance();
            if (manager.getTranslationService() != null && manager.getTranslationService().getTranslationCache().containsKey(key)) {
                return true;
            }
        } catch (Exception ignored) {}
        return delegate.has(key);
    }

    @Override
    public boolean isDefaultRightToLeft() {
        return delegate.isDefaultRightToLeft();
    }

    @Override
    public FormattedCharSequence getVisualOrder(FormattedText formattedText) {
        if (formattedText == null) {
            return FormattedCharSequence.EMPTY;
        }

        String text = formattedText.getString();
        if (shouldTranslateLiteral(text)) {
            try {
                XTranslationManager manager = XTranslationManager.getInstance();
                TranslationService service = manager != null ? manager.getTranslationService() : null;
                if (service != null) {
                    Map<String, String> cache = service.getTranslationCache();
                    String cached = cache.get(text);
                    if (cached != null) {
                        return delegate.getVisualOrder(FormattedText.of(cached));
                    }
                }
            } catch (Exception ignored) {}
        }

        return delegate.getVisualOrder(formattedText);
    }

    private boolean shouldTranslateLiteral(String text) {
        if (text == null || text.length() < 4 || text.length() > 300) {
            return false;
        }
        // Must contain letters and spaces (phrases/sentences/descriptions)
        if (!text.contains(" ") || !LITERAL_FILTER.matcher(text).matches()) {
            return false;
        }
        // Exclude system paths, commands, urls
        if (text.startsWith("/") || text.startsWith("http") || text.contains("{") || text.contains("}") || text.contains("[0-9]")) {
            return false;
        }
        return true;
    }
}
