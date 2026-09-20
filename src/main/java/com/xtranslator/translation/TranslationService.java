package com.xtranslator.translation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.xtranslator.XTranslatorMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Translation service with caching, multi-threading, and batch translation support.
 */
public class TranslationService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int BATCH_SIZE = 100; // Number of translations per batch
    private static final int THREAD_POOL_SIZE = 4; // Safe number of parallel threads

    private final GoogleTranslateClient client;
    private final String targetLanguage;
    private final Map<String, String> translationCache;
    private final Path cacheFile;
    private final int delayMs;

    public TranslationService(String sourceLanguage, String targetLanguage, Path cacheDir, int delayMs) throws IOException {
        this.client = new GoogleTranslateClient(sourceLanguage, targetLanguage);
        this.targetLanguage = targetLanguage != null ? targetLanguage.toLowerCase().trim() : "vi";
        this.delayMs = delayMs;

        // Initialize cache
        Files.createDirectories(cacheDir);
        this.cacheFile = cacheDir.resolve("translation_cache_" + sourceLanguage + "_to_" + targetLanguage + ".json");
        this.translationCache = loadCache();

        XTranslatorMod.LOGGER.info("TranslationService initialized with {} cached translations",
                translationCache.size());
    }

    /**
     * Translates a single key-value pair.
     *
     * @param key   Translation key
     * @param value Original text
     * @return Translated text
     */
    public String translate(String key, String value) {
        // Check cache first
        if (translationCache.containsKey(key)) {
            return translationCache.get(key);
        }

        try {
            String translated = client.translate(value);
            // ConcurrentHashMap does NOT allow null values — must check before put!
            if (translated != null && !translated.isBlank()) {
                translationCache.put(key, translated);
                return translated;
            }
            // Translation returned null/blank — do NOT cache, let it retry later
            return null;
        } catch (Exception e) {
            XTranslatorMod.LOGGER.error("Failed to translate '{}': {}", key, e.getMessage());
            return null; // Return null so callers know it failed
        }
    }

    /**
     * Translates a batch of key-value pairs with multi-threading.
     *
     * @param translations Map of translation key -> original text
     * @return Map of translation key -> translated text
     */
    public Map<String, String> translateBatch(Map<String, String> translations) {
        return translateBatch(translations, null);
    }

    /**
     * Translates a batch of key-value pairs with multi-threading and real-time progress callback.
     *
     * @param translations     Map of translation key -> original text
     * @param progressCallback Callback invoked with number of completed items
     * @return Map of translation key -> translated text
     */
    public Map<String, String> translateBatch(Map<String, String> translations, java.util.function.IntConsumer progressCallback) {
        Map<String, String> results = new ConcurrentHashMap<>();
        Map<String, String> toTranslate = new HashMap<>();

        // Separate cached and non-cached translations
        for (Map.Entry<String, String> entry : translations.entrySet()) {
            String key = entry.getKey();
            if (translationCache.containsKey(key)) {
                results.put(key, translationCache.get(key));
            } else {
                toTranslate.put(key, entry.getValue());
            }
        }

        XTranslatorMod.LOGGER.info("Using {} cached translations, translating {} new entries",
                results.size(), toTranslate.size());

        if (progressCallback != null && !results.isEmpty()) {
            progressCallback.accept(results.size());
        }

        if (toTranslate.isEmpty()) {
            return results;
        }

        // Split into batches
        List<Map.Entry<String, String>> entries = new ArrayList<>(toTranslate.entrySet());
        List<List<Map.Entry<String, String>>> batches = new ArrayList<>();

        for (int i = 0; i < entries.size(); i += BATCH_SIZE) {
            batches.add(entries.subList(i, Math.min(i + BATCH_SIZE, entries.size())));
        }

        XTranslatorMod.LOGGER.info("Processing {} entries in {} batches using {} threads",
                entries.size(), batches.size(), THREAD_POOL_SIZE);

        // Process batches in parallel
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        try {
            List<Future<Map<String, String>>> futures = new ArrayList<>();

            for (int i = 0; i < batches.size(); i++) {
                final int batchIndex = i;
                final List<Map.Entry<String, String>> batch = batches.get(i);

                futures.add(executor.submit(() -> {
                    return processBatch(batch, batchIndex + 1, batches.size(), progressCallback);
                }));
            }

            // Collect results
            for (Future<Map<String, String>> future : futures) {
                try {
                    Map<String, String> batchResults = future.get();
                    results.putAll(batchResults);
                    translationCache.putAll(batchResults);
                } catch (Exception e) {
                    XTranslatorMod.LOGGER.error("Batch translation failed", e);
                }
            }
        } finally {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                XTranslatorMod.LOGGER.error("Translation service interrupted", e);
                Thread.currentThread().interrupt();
            }
        }

        return results;
    }

    /**
     * Processes a single batch of translations.
     */
    private Map<String, String> processBatch(List<Map.Entry<String, String>> batch, int batchNum, int totalBatches, java.util.function.IntConsumer progressCallback) {
        Map<String, String> results = new HashMap<>();

        try {
            // Prepare texts for batch translation
            List<String> textsToTranslate = new ArrayList<>();
            for (Map.Entry<String, String> entry : batch) {
                textsToTranslate.add(entry.getValue());
            }

            // Translate batch with real-time per-item progress
            List<String> translatedTexts = client.translateBatch(textsToTranslate, progressCallback);

            // Map results back to keys
            for (int i = 0; i < batch.size(); i++) {
                String key = batch.get(i).getKey();
                String sourceVal = batch.get(i).getValue();
                String translated = i < translatedTexts.size() ? translatedTexts.get(i) : null;
                // Only save if translation succeeded, is not empty, and is not identical to key
                if (translated != null && !translated.isBlank() && !translated.equals(key)) {
                    // If target is Vietnamese, never save Chinese characters into Vietnamese results
                    if (targetLanguage.startsWith("vi") && LanguageHelper.hasChineseCharacters(translated)) {
                        continue;
                    }
                    results.put(key, translated);
                }
            }

            // Only log every 10th batch to reduce overhead
            if (batchNum % 10 == 0 || batchNum == totalBatches) {
                XTranslatorMod.LOGGER.info("Progress: {}/{} batches completed", batchNum, totalBatches);
            }

            // Delay between batches to avoid rate limiting
            if (delayMs > 0 && batchNum < totalBatches) {
                Thread.sleep(delayMs);
            }

        } catch (Exception e) {
            XTranslatorMod.LOGGER.error("Failed to process batch {}/{}", batchNum, totalBatches, e);
            // DO NOT put raw original texts into results on error!
            // Doing so would corrupt the target language cache/resource pack with untranslated source strings.
        }

        return results;
    }

    /**
     * Loads translation cache from disk and cleanses invalid entries.
     */
    private Map<String, String> loadCache() {
        if (!Files.exists(cacheFile)) {
            XTranslatorMod.LOGGER.info("No cache file found, starting with empty cache");
            return new ConcurrentHashMap<>();
        }

        try {
            String json = Files.readString(cacheFile);
            Map<String, String> cache = GSON.fromJson(json, new TypeToken<Map<String, String>>(){}.getType());
            if (cache != null) {
                // Auto-cleanse cache: Purge any corrupt un-restored tokens (e.g. ⟦P0 Điểm⟧)
                int beforeCorrupt = cache.size();
                cache.entrySet().removeIf(e -> e.getValue().contains("⟦P") || e.getValue().contains("[P") || e.getValue().contains("⟦ P"));
                if (cache.size() < beforeCorrupt) {
                    XTranslatorMod.LOGGER.info("Cleansed {} corrupt un-restored token entries from cache", beforeCorrupt - cache.size());
                }

                // Auto-cleanse cache: If target is Vietnamese, purge any entries containing Chinese characters
                if (targetLanguage.startsWith("vi")) {
                    int before = cache.size();
                    cache.entrySet().removeIf(e -> LanguageHelper.hasChineseCharacters(e.getValue()));
                    if (cache.size() < before) {
                        XTranslatorMod.LOGGER.info("Cleansed {} invalid Chinese entries from Vietnamese translation cache",
                                before - cache.size());
                    }
                }
                XTranslatorMod.LOGGER.info("Loaded {} translations from cache", cache.size());
                return new ConcurrentHashMap<>(cache);
            }
            return new ConcurrentHashMap<>();
        } catch (Exception e) {
            XTranslatorMod.LOGGER.error("Failed to load cache file", e);
            return new ConcurrentHashMap<>();
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean isSaving = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean needsSave = false;

    /**
     * Saves translation cache to disk asynchronously with debouncing.
     */
    public void saveCache() {
        needsSave = true;
        if (isSaving.compareAndSet(false, true)) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    while (needsSave) {
                        needsSave = false;
                        Thread.sleep(600); // Debounce bursts
                        // Snapshot the map to avoid ConcurrentModificationException during serialization
                        Map<String, String> snapshot = new HashMap<>(translationCache);
                        String json = GSON.toJson(snapshot);
                        Files.writeString(cacheFile, json);
                        XTranslatorMod.LOGGER.info("Async saved {} translations to cache", snapshot.size());
                    }
                } catch (Exception e) {
                    XTranslatorMod.LOGGER.error("Failed to async save cache file", e);
                } finally {
                    isSaving.set(false);
                }
            });
        }
    }

    /**
     * Synchronously saves translation cache to disk.
     */
    public void saveCacheSync() {
        try {
            String json = GSON.toJson(translationCache);
            Files.writeString(cacheFile, json);
            XTranslatorMod.LOGGER.info("Saved {} translations to cache", translationCache.size());
        } catch (Exception e) {
            XTranslatorMod.LOGGER.error("Failed to save cache file", e);
        }
    }

    /**
     * Clears the translation cache entirely.
     */
    public void clearCache() {
        translationCache.clear();
        try {
            if (Files.exists(cacheFile)) {
                Files.delete(cacheFile);
                XTranslatorMod.LOGGER.info("Translation cache cleared");
            }
        } catch (Exception e) {
            XTranslatorMod.LOGGER.error("Failed to delete cache file", e);
        }
    }

    /**
     * Clears cached translations for a specific mod/namespace.
     */
    public int clearCacheForNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) return 0;
        String nsLower = namespace.toLowerCase().trim();
        List<String> toRemove = new ArrayList<>();
        for (String key : translationCache.keySet()) {
            String lowerKey = key.toLowerCase();
            if (lowerKey.startsWith(nsLower + ".") || lowerKey.contains("." + nsLower + ".") || lowerKey.startsWith(nsLower + ":") || lowerKey.equals(nsLower)) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            translationCache.remove(key);
        }
        if (!toRemove.isEmpty()) {
            try {
                saveCache();
            } catch (Exception e) {
                XTranslatorMod.LOGGER.error("Failed to save cache after clearing namespace {}", namespace, e);
            }
        }
        return toRemove.size();
    }

    public void putTranslation(String key, String value) {
        translationCache.put(key, value);
    }

    public void putAllTranslations(Map<String, String> map) {
        translationCache.putAll(map);
    }

    public Map<String, String> getTranslationCache() {
        return translationCache;
    }

    /**
     * Gets the number of cached translations.
     */
    public int getCacheSize() {
        return translationCache.size();
    }

    /**
     * Checks if the translation service is available.
     */
    public boolean isAvailable() {
        return client.isAvailable();
    }
}
