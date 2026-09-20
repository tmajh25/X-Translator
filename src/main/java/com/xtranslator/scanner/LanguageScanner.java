package com.xtranslator.scanner;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xtranslator.XTranslatorMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Scans all loaded mods for language files and detects missing translations.
 * Supports auto-detecting available source languages (e.g. en_us, zh_cn, ru_ru)
 * and comparing with target language.
 */
public class LanguageScanner {
    private static final Gson GSON = new Gson();
    private final String preferredSourceLanguage;
    private final String targetLanguage;

    public LanguageScanner(String preferredSourceLanguage, String targetLanguage) {
        this.preferredSourceLanguage = preferredSourceLanguage != null ? preferredSourceLanguage.toLowerCase() : "auto";
        this.targetLanguage = targetLanguage != null ? targetLanguage.toLowerCase() : "en_us";
    }

    /**
     * Scans all available packs (mods + resource packs) and extracts all keys already translated
     * into the target language. This ensures we NEVER overwrite existing good translations!
     */
    public Set<String> scanExistingTargetTranslations(Iterable<PackResources> packResources) {
        Set<String> existing = new HashSet<>();
        for (PackResources pack : packResources) {
            String packId = pack.packId().toLowerCase();
            // Skip our own generated pack so we don't treat our own cache as authoritative source
            if (packId.contains("xtranslator")) {
                continue;
            }

            Set<String> namespaces = pack.getNamespaces(PackType.CLIENT_RESOURCES);
            for (String namespace : namespaces) {
                if ("minecraft".equals(namespace) || "xtranslator".equals(namespace)) {
                    continue;
                }
                Map<String, String> targetLang = loadLanguageFile(pack, namespace, targetLanguage);
                existing.addAll(targetLang.keySet());
            }
        }
        return existing;
    }

    /**
     * Scans all mods and returns a map of namespace -> missing translation keys.
     *
     * @param packResources All available pack resources
     * @return Map of mod namespace to map of translation keys and original values
     */
    public Map<String, Map<String, String>> scanForMissingTranslations(Iterable<PackResources> packResources) {
        Set<String> existingTargetKeys = scanExistingTargetTranslations(packResources);
        return scanForMissingTranslations(packResources, existingTargetKeys);
    }

    public Map<String, Map<String, String>> scanForMissingTranslations(Iterable<PackResources> packResources, Set<String> existingTargetKeys) {
        Map<String, Map<String, String>> missingTranslations = new HashMap<>();

        for (PackResources pack : packResources) {
            String packId = pack.packId();
            if (packId.toLowerCase().contains("xtranslator")) {
                continue;
            }
            XTranslatorMod.LOGGER.debug("Scanning pack: {}", packId);

            // Get all namespaces in this pack
            Set<String> namespaces = pack.getNamespaces(PackType.CLIENT_RESOURCES);

            for (String namespace : namespaces) {
                // Ignore minecraft vanilla namespace and self mod
                if ("minecraft".equals(namespace) || "xtranslator".equals(namespace)) {
                    continue;
                }

                Map<String, String> missing = scanNamespace(pack, namespace, existingTargetKeys);
                if (!missing.isEmpty()) {
                    missingTranslations.computeIfAbsent(namespace, k -> new HashMap<>()).putAll(missing);
                }
            }
        }

        return missingTranslations;
    }

    /**
     * Scans a specific namespace for missing translations.
     * Automatically identifies available source languages if configured or auto.
     */
    private Map<String, String> scanNamespace(PackResources pack, String namespace, Set<String> existingTargetKeys) {
        Map<String, String> missingTranslations = new HashMap<>();

        try {
            // Find all available lang files for this namespace
            List<String> availableLangs = findAvailableLanguages(pack, namespace);
            if (availableLangs.isEmpty()) {
                return missingTranslations;
            }

            // Determine best source language:
            // 1. If preferredSourceLanguage is specified and not "auto", check if it exists
            // 2. Otherwise prefer "en_us" if available
            // 3. Otherwise pick the first non-target language available
            String chosenSource = null;
            if (!"auto".equals(preferredSourceLanguage) && availableLangs.contains(preferredSourceLanguage)) {
                chosenSource = preferredSourceLanguage;
            } else if (availableLangs.contains("en_us") && !"en_us".equals(targetLanguage)) {
                chosenSource = "en_us";
            } else {
                for (String lang : availableLangs) {
                    if (!lang.equalsIgnoreCase(targetLanguage)) {
                        chosenSource = lang;
                        break;
                    }
                }
            }

            if (chosenSource == null) {
                // No alternative source language available
                return missingTranslations;
            }

            // Load source language translations
            Map<String, String> sourceTranslations = loadLanguageFile(pack, namespace, chosenSource);
            if (sourceTranslations.isEmpty()) {
                return missingTranslations;
            }

            // Load target language translations from this pack (if exists)
            Map<String, String> targetTranslations = loadLanguageFile(pack, namespace, targetLanguage);

            // Find missing keys
            for (Map.Entry<String, String> entry : sourceTranslations.entrySet()) {
                String key = entry.getKey();
                // If key is ALREADY translated in ANY active pack or this pack, SKIP IT!
                if (!existingTargetKeys.contains(key) && !targetTranslations.containsKey(key)) {
                    missingTranslations.put(key, entry.getValue());
                }
            }

        } catch (Exception e) {
            XTranslatorMod.LOGGER.error("Error scanning namespace: {}", namespace, e);
        }

        return missingTranslations;
    }

    /**
     * Finds all available language codes for a given namespace in the pack.
     */
    private List<String> findAvailableLanguages(PackResources pack, String namespace) {
        List<String> langs = new ArrayList<>();
        try {
            pack.listResources(PackType.CLIENT_RESOURCES, namespace, "lang", (location, streamSupplier) -> {
                String path = location.getPath(); // e.g. "lang/en_us.json"
                if (path.startsWith("lang/") && path.endsWith(".json")) {
                    String langCode = path.substring("lang/".length(), path.length() - ".json".length()).toLowerCase();
                    if (!langs.contains(langCode)) {
                        langs.add(langCode);
                    }
                }
            });
        } catch (Exception e) {
            XTranslatorMod.LOGGER.debug("Could not list resources for namespace {}", namespace);
        }
        return langs;
    }

    /**
     * Loads a language file from a resource pack.
     */
    private Map<String, String> loadLanguageFile(PackResources pack, String namespace, String language) {
        Map<String, String> translations = new HashMap<>();

        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(namespace, "lang/" + language + ".json");

        try {
            IoSupplier<InputStream> resource = pack.getResource(PackType.CLIENT_RESOURCES, location);
            if (resource == null) {
                return translations;
            }

            try (InputStream inputStream = resource.get();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

                JsonObject jsonObject = GSON.fromJson(reader, JsonObject.class);
                if (jsonObject != null) {
                    jsonObject.entrySet().forEach(entry -> {
                        if (entry.getValue().isJsonPrimitive()) {
                            translations.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    });
                }
            }

        } catch (IOException e) {
            XTranslatorMod.LOGGER.debug("Language file not found for namespace '{}' and language '{}'", namespace, language);
        } catch (Exception e) {
            XTranslatorMod.LOGGER.error("Error loading language file for namespace '{}' and language '{}'", namespace, language, e);
        }

        return translations;
    }

    public static Map<String, IModInfo> getAllMods() {
        Map<String, IModInfo> mods = new HashMap<>();
        ModList.get().getMods().forEach(modInfo -> mods.put(modInfo.getModId(), modInfo));
        return mods;
    }
}
