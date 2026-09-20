package com.xtranslator;

import com.xtranslator.chat.TranslationProgress;
import com.xtranslator.config.ModConfig;
import com.xtranslator.resourcepack.ResourcePackActivator;
import com.xtranslator.resourcepack.ResourcePackGenerator;
import com.xtranslator.scanner.FTBQuestsScanner;
import com.xtranslator.scanner.LanguageScanner;
import com.xtranslator.translation.TranslationService;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackResources;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main manager for the auto-translation process.
 * Coordinates scanning, translation, and resource pack generation.
 */
public class XTranslationManager {
    private static volatile XTranslationManager instance;
    private static final AtomicBoolean translationInProgress = new AtomicBoolean(false);

    private final Path gameDirectory;
    private final TranslationService translationService;
    private final ResourcePackGenerator resourcePackGenerator;
    private final String sourceLanguage;
    private final String targetLanguage;
    private final Map<String, String> untranslatedKeysCache = new ConcurrentHashMap<>();
    private final Set<String> existingTargetKeys = ConcurrentHashMap.newKeySet();

    public boolean isUntranslatedKey(String key) {
        return untranslatedKeysCache.containsKey(key);
    }

    public boolean isAlreadyTranslated(String key) {
        return existingTargetKeys.contains(key);
    }

    public Set<String> getExistingTargetKeys() {
        return existingTargetKeys;
    }

    public String getSourceTextFor(String key) {
        return untranslatedKeysCache.get(key);
    }

    public Map<String, String> getUntranslatedKeysCache() {
        return untranslatedKeysCache;
    }

    private XTranslationManager(Path gameDirectory) {
        this.gameDirectory = gameDirectory;

        // 1. Resolve source language (from config, default en_us)
        String configSource = ModConfig.SOURCE_LANGUAGE.get();
        this.sourceLanguage = (configSource != null && !configSource.trim().isEmpty())
                ? configSource.trim().toLowerCase()
                : "auto";

        // 2. Resolve target language (from config if specified, else from Minecraft settings)
        String configTarget = ModConfig.TARGET_LANGUAGE.get();
        if (configTarget != null && !configTarget.trim().isEmpty() && !configTarget.equalsIgnoreCase("auto")) {
            this.targetLanguage = configTarget.trim().toLowerCase();
        } else {
            this.targetLanguage = getMinecraftLanguage();
        }
        TranslationProgress.setTargetLanguage(this.targetLanguage);

        XTranslatorMod.LOGGER.info("XTranslator language config: source = {}, target = {}",
                sourceLanguage, targetLanguage);

        String shortSource = sourceLanguage.contains("_") ? sourceLanguage.split("_")[0] : sourceLanguage;
        String shortTarget = targetLanguage.contains("_") ? targetLanguage.split("_")[0] : targetLanguage;

        int delayMs = ModConfig.TRANSLATION_DELAY_MS.get();
        Path cacheDir = gameDirectory.resolve("xtranslator");

        try {
            this.translationService = new TranslationService(shortSource, shortTarget, cacheDir, delayMs);
            XTranslatorMod.LOGGER.info("Translation service initialized successfully for {} -> {}",
                    shortSource, shortTarget);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize translation service", e);
        }

        this.resourcePackGenerator = new ResourcePackGenerator(gameDirectory, targetLanguage);

        // Auto-save cache on game exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (translationService != null) {
                    translationService.saveCache();
                }
            } catch (Throwable ignored) {
            }
        }, "XTranslator-ShutdownHook"));

        // Auto-index missing translation keys in background for real-time live interception
        Thread indexThread = new Thread(() -> {
            try {
                Thread.sleep(1200);
                Map<String, Map<String, String>> missing = scanMissingTranslations();
                for (Map<String, String> map : missing.values()) {
                    untranslatedKeysCache.putAll(map);
                }
                XTranslatorMod.LOGGER.info("XTranslator successfully indexed {} untranslated keys for live in-memory translation", untranslatedKeysCache.size());
            } catch (Exception e) {
                XTranslatorMod.LOGGER.debug("Error during background index: {}", e.getMessage());
            }
        }, "XTranslator-LiveIndexer");
        indexThread.setDaemon(true);
        indexThread.start();
    }

    /**
     * Gets the current language from Minecraft settings.
     *
     * @return Language code (e.g., "ru_ru", "uk_ua", "vi_vn", "en_us")
     */
    private String getMinecraftLanguage() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.options != null) {
            String languageCode = minecraft.options.languageCode;
            XTranslatorMod.LOGGER.info("Minecraft language code: {}", languageCode);
            return languageCode;
        }

        XTranslatorMod.LOGGER.warn("Unable to get Minecraft language, falling back to en_us");
        return "en_us";
    }

    public static synchronized XTranslationManager getInstance(Path gameDirectory) {
        if (instance == null) {
            instance = new XTranslationManager(gameDirectory);
        }
        return instance;
    }

    public static synchronized XTranslationManager getInstance() {
        if (instance == null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                instance = new XTranslationManager(mc.gameDirectory.toPath());
            } else {
                throw new IllegalStateException("Minecraft gameDirectory not available");
            }
        }
        return instance;
    }

    public static synchronized void resetInstance() {
        instance = null;
    }

    /**
     * Starts the auto-translation process.
     */
    public void startTranslation() {
        startTranslation(false);
    }

    public void startTranslation(boolean forceFull) {
        if (!ModConfig.ENABLED.get()) {
            XTranslatorMod.LOGGER.info("Auto-translation is disabled in config");
            return;
        }

        // Don't translate if target is the same as source language
        if (isSameLanguage(sourceLanguage, targetLanguage)) {
            XTranslatorMod.LOGGER.info("Target language {} matches or compatible with source language {}, skipping translation", targetLanguage, sourceLanguage);
            return;
        }

        if (!translationInProgress.compareAndSet(false, true)) {
            XTranslatorMod.LOGGER.warn("Translation or indexing already in progress, skipping");
            return;
        }

        String mode = ModConfig.TRANSLATION_MODE.get();
        boolean isFullMode = forceFull || "FULL".equalsIgnoreCase(mode);

        Thread translationThread = new Thread(() -> {
            try {
                // Step 1: Always scan and index missing translations for live dynamic interception
                TranslationProgress.setPhase("Indexing mods...");
                XTranslatorMod.LOGGER.info("Scanning for missing translations to index...");
                Map<String, Map<String, String>> missingTranslations = scanMissingTranslations();
                for (Map<String, String> map : missingTranslations.values()) {
                    untranslatedKeysCache.putAll(map);
                }
                XTranslatorMod.LOGGER.info("Indexed {} missing translation keys for live dynamic interception", untranslatedKeysCache.size());

                if (!isFullMode) {
                    String langName = TranslationProgress.getLanguageDisplayName(targetLanguage);
                    TranslationProgress.sendChatMessage(net.minecraft.network.chat.Component.translatable("xtranslator.chat.prefix")
                            .append(net.minecraft.network.chat.Component.translatable("xtranslator.chat.indexing", untranslatedKeysCache.size(), langName)));
                    return;
                }

                performFullTranslation(missingTranslations);
            } catch (Exception e) {
                XTranslatorMod.LOGGER.error("Error during translation process", e);
                TranslationProgress.reset();
            } finally {
                translationInProgress.set(false);
            }
        }, "XTranslator-Worker");

        translationThread.setDaemon(true);
        translationThread.start();
    }

    /**
     * Performs the complete full batch translation process.
     */
    private void performFullTranslation(Map<String, Map<String, String>> missingTranslations) {
        XTranslatorMod.LOGGER.info("Starting full batch translation ({} -> {})...", sourceLanguage, targetLanguage);

        try {
            if (missingTranslations.isEmpty()) {
                XTranslatorMod.LOGGER.info("No missing translations found!");
                TranslationProgress.sendChatMessage("§a[XTranslator] No missing translations found");
                TranslationProgress.reset();
                return;
            }

            int totalMissing = missingTranslations.values().stream()
                    .mapToInt(Map::size)
                    .sum();
            XTranslatorMod.LOGGER.info("Found {} missing translations across {} namespaces",
                    totalMissing, missingTranslations.size());

            // Initialize progress tracking
            TranslationProgress.startTranslation(totalMissing, "Toàn bộ mod");

            // Step 2: Translate missing entries
            TranslationProgress.setPhase("Translating mods...");
            XTranslatorMod.LOGGER.info("Translating missing entries...");
            Map<String, Map<String, String>> translatedEntries = new HashMap<>();

            for (Map.Entry<String, Map<String, String>> entry : missingTranslations.entrySet()) {
                if (TranslationProgress.isCancelled()) {
                    XTranslatorMod.LOGGER.info("Translation cancelled by user");
                    TranslationProgress.reset();
                    return;
                }

                String namespace = entry.getKey();
                Map<String, String> toTranslate = entry.getValue();

                TranslationProgress.setPhase(namespace);
                XTranslatorMod.LOGGER.info("Translating namespace: {} ({} entries)",
                        namespace, toTranslate.size());

                Map<String, String> translated = translationService.translateBatch(toTranslate, TranslationProgress::addProgress);
                translatedEntries.put(namespace, translated);
            }

            // Save translation cache
            translationService.saveCache();

            // Step 3: Generate resource pack
            TranslationProgress.setPhase("Generating resource pack...");
            XTranslatorMod.LOGGER.info("Generating resource pack...");
            resourcePackGenerator.generateResourcePack(translatedEntries);

            // Step 4: Translate FTB Quests
            TranslationProgress.setPhase("Scanning FTB Quests...");
            TranslationProgress.sendChatMessage("§a[XTranslator] §fScanning FTB Quests...");
            XTranslatorMod.LOGGER.info("Scanning FTB Quests...");
            FTBQuestsScanner questsScanner = new FTBQuestsScanner(sourceLanguage, targetLanguage);
            Map<String, String> missingQuests = questsScanner.scanForMissingTranslations(gameDirectory);

            if (!missingQuests.isEmpty()) {
                TranslationProgress.setPhase("Translating FTB Quests...");
                TranslationProgress.sendChatMessage("§a[XTranslator] §fTranslating §e" + missingQuests.size() + " §fFTB Quests entries...");
                XTranslatorMod.LOGGER.info("Translating {} FTB Quests entries...", missingQuests.size());
                Map<String, String> translatedQuests = translationService.translateBatch(missingQuests, TranslationProgress::addProgress);

                translationService.saveCache();
                questsScanner.writeTranslations(gameDirectory, translatedQuests);
                XTranslatorMod.LOGGER.info("FTB Quests translation complete!");
                TranslationProgress.sendChatMessage("§a[XTranslator] §fFTB Quests translation complete!");
            } else {
                XTranslatorMod.LOGGER.info("No missing FTB Quests translations found");
                TranslationProgress.sendChatMessage("§a[XTranslator] §fNo missing FTB Quests translations");
            }

            // Step 5: Activate resource pack
            if (ModConfig.AUTO_ACTIVATE_RESOURCEPACK.get()) {
                TranslationProgress.setPhase("Activating resource pack...");
                XTranslatorMod.LOGGER.info("Activating resource pack...");
                Minecraft.getInstance().execute(() -> {
                    ResourcePackActivator.activateResourcePack();
                });
            }

            XTranslatorMod.LOGGER.info("Auto-translation process complete!");
            TranslationProgress.completeTranslation();

        } catch (Exception e) {
            XTranslatorMod.LOGGER.error("Failed to complete translation process", e);
            TranslationProgress.reset();
        }
    }

    /**
     * Scans all loaded mods for missing translations, accurately identifying pre-existing translations.
     */
    private Map<String, Map<String, String>> scanMissingTranslations() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            XTranslatorMod.LOGGER.error("Minecraft instance not available");
            return new HashMap<>();
        }

        LanguageScanner scanner = new LanguageScanner(sourceLanguage, targetLanguage);

        Iterable<PackResources> packResources = minecraft.getResourceManager()
                .listPacks()
                .toList();

        Set<String> existing = scanner.scanExistingTargetTranslations(packResources);
        existingTargetKeys.clear();
        existingTargetKeys.addAll(existing);
        XTranslatorMod.LOGGER.info("Identified {} pre-existing translations in target language '{}' across all packs",
                existingTargetKeys.size(), targetLanguage);

        return scanner.scanForMissingTranslations(packResources, existingTargetKeys);
    }

    public Map<String, Map<String, String>> scanMissingTranslationsPublic() {
        return scanMissingTranslations();
    }

    public void addPriorityTranslation(String key, String translated) {
        if (targetLanguage.startsWith("vi") && com.xtranslator.translation.LanguageHelper.hasChineseCharacters(translated)) {
            return; // Reject Chinese contamination
        }
        translationService.putTranslation(key, translated);
    }

    public void appendTranslationsToResourcePack(String namespace, Map<String, String> translations) {
        if (translations == null || translations.isEmpty()) return;
        Map<String, String> sanitized = new HashMap<>(translations);
        if (targetLanguage.startsWith("vi")) {
            sanitized.entrySet().removeIf(e -> com.xtranslator.translation.LanguageHelper.hasChineseCharacters(e.getValue())
                    || e.getValue().equals(e.getKey()) || isAlreadyTranslated(e.getKey()));
        } else {
            sanitized.entrySet().removeIf(e -> isAlreadyTranslated(e.getKey()));
        }
        if (sanitized.isEmpty()) return;

        Map<String, Map<String, String>> singleMap = new HashMap<>();
        singleMap.put(namespace, sanitized);
        try {
            resourcePackGenerator.generateResourcePack(singleMap);
        } catch (Exception e) {
            XTranslatorMod.LOGGER.debug("Failed to append translations to pack: {}", e.getMessage());
        }
    }

    public void editSingleTranslation(String key, String newTranslation) {
        translationService.putTranslation(key, newTranslation);
        translationService.saveCache();

        String namespace = "minecraft";
        String[] parts = key.split("\\.");
        if (parts.length >= 2) {
            if (parts[0].equals("item") || parts[0].equals("block") || parts[0].equals("entity")) {
                if (parts.length >= 3) {
                    namespace = parts[1];
                }
            } else {
                namespace = parts[0];
            }
        }

        Map<String, Map<String, String>> singleMap = new HashMap<>();
        singleMap.computeIfAbsent(namespace, k -> new HashMap<>()).put(key, newTranslation);
        try {
            resourcePackGenerator.generateResourcePack(singleMap);
        } catch (Exception e) {
            XTranslatorMod.LOGGER.error("Failed to update resource pack for edited key", e);
        }

        Minecraft.getInstance().execute(() -> {
            Minecraft.getInstance().reloadResourcePacks();
        });
    }

    public void applyCustomTranslations(Map<String, String> customMap) {
        if (customMap == null || customMap.isEmpty()) return;

        translationService.putAllTranslations(customMap);
        translationService.saveCache();

        Map<String, Map<String, String>> grouped = new HashMap<>();
        for (Map.Entry<String, String> entry : customMap.entrySet()) {
            String key = entry.getKey();
            String namespace = "minecraft";
            String[] parts = key.split("\\.");
            if (parts.length >= 2) {
                if (parts[0].equals("item") || parts[0].equals("block") || parts[0].equals("entity")) {
                    if (parts.length >= 3) {
                        namespace = parts[1];
                    }
                } else {
                    namespace = parts[0];
                }
            }
            grouped.computeIfAbsent(namespace, k -> new HashMap<>()).put(key, entry.getValue());
        }

        try {
            resourcePackGenerator.generateResourcePack(grouped);
        } catch (Exception e) {
            XTranslatorMod.LOGGER.error("Failed to generate resource pack for custom translations", e);
        }

        Minecraft.getInstance().execute(() -> {
            Minecraft.getInstance().reloadResourcePacks();
        });
    }

    /**
     * Clears translations for a specific mod or all if modId is null/empty/"all".
     */
    public void clearTranslations(String targetModId) {
        if (targetModId == null || targetModId.isBlank() || targetModId.equalsIgnoreCase("all")) {
            clearAllTranslations();
            return;
        }

        final String searchId = targetModId.trim().toLowerCase();
        int cleared = translationService.clearCacheForNamespace(searchId);
        TranslationProgress.sendChatMessage("§a[XTranslator] §fĐã xóa §e" + cleared + " §fdòng dịch của mod '§e" + searchId + "§f'! Dùng §a/xtrans mod " + searchId + "§f để dịch lại.");
    }

    /**
     * Clears all translation cache, resource packs, and resets the state.
     */
    public void clearAllTranslations() {
        translationService.clearCache();
        try {
            resourcePackGenerator.deleteResourcePack();
        } catch (Exception e) {
            XTranslatorMod.LOGGER.debug("Error deleting resource pack: {}", e.getMessage());
        }
        try {
            FTBQuestsScanner questsScanner = new FTBQuestsScanner(sourceLanguage, targetLanguage);
            questsScanner.removeTranslations(gameDirectory);
        } catch (Exception e) {
            XTranslatorMod.LOGGER.debug("Error removing FTB Quests translations: {}", e.getMessage());
        }
        untranslatedKeysCache.clear();

        Thread indexThread = new Thread(() -> {
            try {
                Map<String, Map<String, String>> missing = scanMissingTranslations();
                for (Map<String, String> map : missing.values()) {
                    untranslatedKeysCache.putAll(map);
                }
            } catch (Exception ignored) {
            }
        }, "XTranslator-Reindexer");
        indexThread.setDaemon(true);
        indexThread.start();

        Minecraft.getInstance().execute(() -> {
            try {
                Minecraft.getInstance().reloadResourcePacks();
            } catch (Exception ignored) {
            }
        });

        TranslationProgress.sendChatMessage("§a[XTranslator] §fĐã xóa sạch toàn bộ bản dịch & cache (đã reset về ban đầu)!");
    }

    /**
     * Forces a re-translation of all content.
     */
    public void forceRetranslate() {
        clearAllTranslations();
        startTranslation();
    }

    public TranslationService getTranslationService() {
        return translationService;
    }

    public ResourcePackGenerator getResourcePackGenerator() {
        return resourcePackGenerator;
    }

    public boolean isTranslationInProgress() {
        return translationInProgress.get();
    }

    private boolean isSameLanguage(String source, String target) {
        if (source == null || target == null) return false;
        if (target.equalsIgnoreCase(source)) return true;
        if ("auto".equalsIgnoreCase(source)) {
            return target.equalsIgnoreCase("en_us") || target.startsWith("en_") || target.equals("en");
        }
        String shortSrc = source.contains("_") ? source.split("_")[0] : source;
        String shortTgt = target.contains("_") ? target.split("_")[0] : target;
        return shortSrc.equalsIgnoreCase(shortTgt);
    }

    public String getSourceLanguage() {
        return sourceLanguage;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    /**
     * Translates only a specific mod by mod ID / namespace, or all matching mods if wildcard/keyword.
     */
    public void translateSpecificMod(String targetModId) {
        if (targetModId == null || targetModId.isBlank()) {
            TranslationProgress.sendChatMessage("§c[XTranslator] §fVui lòng nhập tên mod ID (Ví dụ: /xtrans mod tensura hoặc /xtrans match create)");
            return;
        }

        final String searchId = targetModId.trim().toLowerCase();
        if (searchId.endsWith("*")) {
            translateModsByKeyword(searchId.replace("*", ""));
            return;
        }

        Thread modWorker = new Thread(() -> {
            try {
                TranslationProgress.sendChatMessage("§a[XTranslator] §fĐang tìm kiếm dữ liệu ngôn ngữ cho mod: §e" + searchId + "§f...");
                Map<String, Map<String, String>> allMissing = scanMissingTranslations();

                // 1. Check exact match first
                String matchedNamespace = null;
                Map<String, String> toTranslate = null;

                for (Map.Entry<String, Map<String, String>> entry : allMissing.entrySet()) {
                    String ns = entry.getKey().toLowerCase();
                    if (ns.equals(searchId)) {
                        matchedNamespace = entry.getKey();
                        toTranslate = entry.getValue();
                        break;
                    }
                }

                // 2. If no exact match, count partial matches
                if (matchedNamespace == null) {
                    List<String> partialMatches = new ArrayList<>();
                    for (String ns : allMissing.keySet()) {
                        if (ns.toLowerCase().contains(searchId)) {
                            partialMatches.add(ns);
                        }
                    }

                    if (partialMatches.size() > 1) {
                        // Multiple mods match keyword, translate all of them!
                        TranslationProgress.sendChatMessage("§a[XTranslator] §fTìm thấy §e" + partialMatches.size() + " §fmod chứa từ khóa '§e" + searchId + "§f'. Bắt đầu dịch tất cả...");
                        translateModsByKeyword(searchId);
                        return;
                    } else if (partialMatches.size() == 1) {
                        matchedNamespace = partialMatches.get(0);
                        toTranslate = allMissing.get(matchedNamespace);
                    }
                }

                if (matchedNamespace == null || toTranslate == null || toTranslate.isEmpty()) {
                    String langName = TranslationProgress.getLanguageDisplayName(targetLanguage);
                    TranslationProgress.sendChatMessage("§e[XTranslator] §fKhông tìm thấy dòng chưa dịch nào cho mod '§c" + searchId + "§f' (Có thể mod đã có đủ " + langName + " hoặc sai tên mod ID).");
                    return;
                }

                int total = toTranslate.size();
                TranslationProgress.startTranslation(total, matchedNamespace, this.targetLanguage);

                Map<String, String> translated = translationService.translateBatch(toTranslate, TranslationProgress::addProgress);
                translationService.putAllTranslations(translated);
                translationService.saveCache();
                appendTranslationsToResourcePack(matchedNamespace, translated);

                TranslationProgress.completeTranslation();
            } catch (Exception e) {
                XTranslatorMod.LOGGER.error("Error translating specific mod {}", searchId, e);
                TranslationProgress.sendChatMessage("§cLỗi khi dịch mod: " + e.getMessage());
                TranslationProgress.reset();
            }
        }, "XTranslator-ModWorker");

        modWorker.setDaemon(true);
        modWorker.start();
    }

    /**
     * Translates all mods whose mod ID/namespace contains the specified keyword.
     */
    public void translateModsByKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            TranslationProgress.sendChatMessage("§c[XTranslator] §fVui lòng nhập từ khóa tìm kiếm (Ví dụ: /xtrans match create)");
            return;
        }

        final String searchKeyword = keyword.trim().toLowerCase().replace("*", "");
        if (searchKeyword.isEmpty()) {
            TranslationProgress.sendChatMessage("§c[XTranslator] §fTừ khóa không hợp lệ.");
            return;
        }

        Thread keywordWorker = new Thread(() -> {
            try {
                String langName = TranslationProgress.getLanguageDisplayName(targetLanguage);
                TranslationProgress.sendChatMessage("§a[XTranslator] §fĐang tìm kiếm tất cả mod chứa từ khóa '§e" + searchKeyword + "§f'...");
                Map<String, Map<String, String>> allMissing = scanMissingTranslations();

                // Find all matching namespaces
                Map<String, Map<String, String>> matchedMods = new LinkedHashMap<>();
                int totalLines = 0;

                for (Map.Entry<String, Map<String, String>> entry : allMissing.entrySet()) {
                    String ns = entry.getKey().toLowerCase();
                    if (ns.contains(searchKeyword) && !entry.getValue().isEmpty()) {
                        matchedMods.put(entry.getKey(), entry.getValue());
                        totalLines += entry.getValue().size();
                    }
                }

                if (matchedMods.isEmpty()) {
                    TranslationProgress.sendChatMessage("§e[XTranslator] §fKhông tìm thấy mod nào chứa từ khóa '§c" + searchKeyword + "§f' cần dịch sang " + langName + " (hoặc các mod đã dịch đủ).");
                    return;
                }

                List<String> modNames = new ArrayList<>(matchedMods.keySet());
                String modListStr = String.join(", ", modNames);
                TranslationProgress.sendChatMessage("§a[XTranslator] §fTìm thấy §e" + matchedMods.size() + " §fmod chứa '§e" + searchKeyword + "§f' (tổng cộng §6" + totalLines + " §fdòng):\n§7" + modListStr);

                TranslationProgress.startTranslation(totalLines, "Từ khóa: " + searchKeyword, this.targetLanguage);

                for (Map.Entry<String, Map<String, String>> entry : matchedMods.entrySet()) {
                    if (TranslationProgress.isCancelled()) {
                        TranslationProgress.reset();
                        return;
                    }

                    String namespace = entry.getKey();
                    Map<String, String> toTranslate = entry.getValue();

                    TranslationProgress.setPhase(namespace);
                    Map<String, String> translated = translationService.translateBatch(toTranslate, TranslationProgress::addProgress);
                    translationService.putAllTranslations(translated);
                    appendTranslationsToResourcePack(namespace, translated);
                }

                translationService.saveCache();
                TranslationProgress.completeTranslation();
                TranslationProgress.sendChatMessage("§a[XTranslator] §f✔ Đã dịch xong toàn bộ §e" + matchedMods.size() + " §fmod theo từ khóa '§e" + searchKeyword + "§f' sang §a" + langName + " §f(§a" + totalLines + " §fdòng)!");
            } catch (Exception e) {
                XTranslatorMod.LOGGER.error("Error translating mods with keyword {}", searchKeyword, e);
                TranslationProgress.sendChatMessage("§cLỗi khi dịch mod theo từ khóa: " + e.getMessage());
                TranslationProgress.reset();
            }
        }, "XTranslator-KeywordWorker");

        keywordWorker.setDaemon(true);
        keywordWorker.start();
    }

    /**
     * Lists all mods with missing translations (supports optional keyword filter).
     */
    public void listModsWithMissingTranslations() {
        listModsWithMissingTranslations(null);
    }

    public void listModsWithMissingTranslations(String filter) {
        final String searchFilter = (filter != null && !filter.isBlank()) ? filter.trim().toLowerCase() : null;

        Thread listWorker = new Thread(() -> {
            try {
                String langName = TranslationProgress.getLanguageDisplayName(targetLanguage);
                if (searchFilter != null) {
                    TranslationProgress.sendChatMessage("§a[XTranslator] §fĐang tìm các mod chứa từ khóa '§e" + searchFilter + "§f' chưa có " + langName + "...");
                } else {
                    TranslationProgress.sendChatMessage("§a[XTranslator] §fĐang quét danh sách các mod chưa có " + langName + " §7(" + targetLanguage + ")§f...");
                }

                Map<String, Map<String, String>> allMissing = scanMissingTranslations();
                if (allMissing.isEmpty()) {
                    TranslationProgress.sendChatMessage("§a[XTranslator] §fTất cả các mod đều đã có đủ " + langName + "!");
                    return;
                }

                Map<String, Integer> matched = new LinkedHashMap<>();
                int totalMissingLines = 0;

                for (Map.Entry<String, Map<String, String>> entry : allMissing.entrySet()) {
                    if (!entry.getValue().isEmpty()) {
                        String ns = entry.getKey();
                        if (searchFilter == null || ns.toLowerCase().contains(searchFilter)) {
                            matched.put(ns, entry.getValue().size());
                            totalMissingLines += entry.getValue().size();
                        }
                    }
                }

                if (matched.isEmpty()) {
                    if (searchFilter != null) {
                        TranslationProgress.sendChatMessage("§e[XTranslator] §fKhông có mod nào chứa từ khóa '§c" + searchFilter + "§f' cần dịch sang " + langName + ".");
                    } else {
                        TranslationProgress.sendChatMessage("§a[XTranslator] §fTất cả các mod đều đã có đủ " + langName + "!");
                    }
                    return;
                }

                StringBuilder sb = new StringBuilder();
                if (searchFilter != null) {
                    sb.append("§a[XTranslator] §fTìm thấy §e").append(matched.size())
                      .append(" §fmod chứa '§e").append(searchFilter)
                      .append("§f' (tổng §6").append(totalMissingLines).append(" §fdòng):\n");
                } else {
                    sb.append("§a[XTranslator] §fDanh sách mod cần dịch sang §a").append(langName).append("§f:\n");
                }

                int count = 0;
                for (Map.Entry<String, Integer> entry : matched.entrySet()) {
                    sb.append("§e- §f").append(entry.getKey()).append(" §7(").append(entry.getValue()).append(" dòng)\n");
                    count++;
                    if (count >= 25) {
                        sb.append("§7... và còn các mod khác.\n");
                        break;
                    }
                }

                if (searchFilter != null) {
                    sb.append("§7💡 Dùng §a/xtrans match ").append(searchFilter).append(" §7để dịch toàn bộ các mod này.");
                } else {
                    sb.append("§7💡 Dùng §a/xtrans mod <tên_mod> §7hoặc §a/xtrans match <từ_khóa> §7để dịch.");
                }

                TranslationProgress.sendChatMessage(sb.toString().trim());
            } catch (Exception e) {
                TranslationProgress.sendChatMessage("§cLỗi khi quét danh sách mod: " + e.getMessage());
            }
        }, "XTranslator-ListWorker");

        listWorker.setDaemon(true);
        listWorker.start();
    }
}
