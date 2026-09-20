package com.xtranslator.resourcepack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.xtranslator.XTranslatorMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Generates a resource pack with translated language files.
 */
public class ResourcePackGenerator {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int PACK_FORMAT = 34; // Minecraft 1.21 pack format

    private final Path resourcePackPath;
    private final String targetLanguage;
    private final String targetLanguageFile;

    public ResourcePackGenerator(Path gameDirectory, String targetLanguage) {
        this.resourcePackPath = gameDirectory.resolve("resourcepacks").resolve("XTranslator");
        this.targetLanguage = targetLanguage != null ? targetLanguage.toLowerCase() : "en_us";
        
        // Normalize for Minecraft language file format (e.g. "vi_vn", "ru_ru", "zh_cn", "ja_jp")
        if (this.targetLanguage.contains("_")) {
            this.targetLanguageFile = this.targetLanguage;
        } else if (this.targetLanguage.contains("-")) {
            this.targetLanguageFile = this.targetLanguage.replace('-', '_');
        } else {
            this.targetLanguageFile = getFullLanguageCode(this.targetLanguage);
        }
    }

    private static String getFullLanguageCode(String shortCode) {
        return switch (shortCode) {
            case "vi" -> "vi_vn";
            case "ru" -> "ru_ru";
            case "zh" -> "zh_cn";
            case "ja" -> "ja_jp";
            case "ko" -> "ko_kr";
            case "de" -> "de_de";
            case "fr" -> "fr_fr";
            case "es" -> "es_es";
            case "pt" -> "pt_br";
            default -> shortCode + "_" + shortCode;
        };
    }

    /**
     * Generates a resource pack with all translations.
     *
     * @param translations Map of namespace -> (translation key -> translated value)
     */
    public void generateResourcePack(Map<String, Map<String, String>> translations) throws IOException {
        XTranslatorMod.LOGGER.info("Generating resource pack at: {}", resourcePackPath);

        // Create resource pack directory
        Files.createDirectories(resourcePackPath);

        // Generate pack.mcmeta
        generatePackMcmeta();

        // Generate language files for each namespace
        for (Map.Entry<String, Map<String, String>> entry : translations.entrySet()) {
            String namespace = entry.getKey();
            Map<String, String> namespaceTranslations = entry.getValue();

            if (!namespaceTranslations.isEmpty()) {
                generateLanguageFile(namespace, namespaceTranslations);
            }
        }

        XTranslatorMod.LOGGER.info("Resource pack generation complete!");
    }

    /**
     * Generates the pack.mcmeta file for the resource pack.
     */
    private void generatePackMcmeta() throws IOException {
        JsonObject packMeta = new JsonObject();
        JsonObject pack = new JsonObject();

        pack.addProperty("pack_format", PACK_FORMAT);
        pack.addProperty("description", "Auto-generated translations by XTranslator");

        packMeta.add("pack", pack);

        Path metaFile = resourcePackPath.resolve("pack.mcmeta");
        Files.writeString(metaFile, GSON.toJson(packMeta));

        XTranslatorMod.LOGGER.debug("Generated pack.mcmeta");
    }

    /**
     * Generates a language file for a specific namespace.
     *
     * @param namespace    The mod namespace
     * @param translations Map of translation key -> translated value
     */
    private void generateLanguageFile(String namespace, Map<String, String> translations) throws IOException {
        // Create directory structure: assets/<namespace>/lang/
        Path langDir = resourcePackPath.resolve("assets").resolve(namespace).resolve("lang");
        Files.createDirectories(langDir);

        // Create language file (e.g., vi_vn.json)
        Path langFile = langDir.resolve(targetLanguageFile + ".json");

        // Convert translations to JSON, preserving valid existing entries if file already exists
        JsonObject translationJson = new JsonObject();
        boolean isVietnamese = targetLanguageFile.startsWith("vi");

        if (Files.exists(langFile)) {
            try {
                String existing = Files.readString(langFile);
                JsonObject oldObj = GSON.fromJson(existing, JsonObject.class);
                if (oldObj != null) {
                    oldObj.entrySet().forEach(e -> {
                        String val = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : "";
                        // If Vietnamese, never keep Chinese text in vi_vn.json
                        if (isVietnamese && com.xtranslator.translation.LanguageHelper.hasChineseCharacters(val)) {
                            return;
                        }
                        translationJson.add(e.getKey(), e.getValue());
                    });
                }
            } catch (Exception ignored) {}
        }

        translations.forEach((k, v) -> {
            if (isVietnamese && com.xtranslator.translation.LanguageHelper.hasChineseCharacters(v)) {
                return; // Do not write Chinese into Vietnamese language file
            }
            translationJson.addProperty(k, v);
        });

        // Write to file
        Files.writeString(langFile, GSON.toJson(translationJson));

        XTranslatorMod.LOGGER.info("Generated language file for namespace '{}' with {} translations",
                namespace, translationJson.size());
    }

    /**
     * Checks if the resource pack already exists.
     */
    public boolean resourcePackExists() {
        return Files.exists(resourcePackPath) && Files.isDirectory(resourcePackPath);
    }

    /**
     * Deletes the existing resource pack.
     */
    public void deleteResourcePack() throws IOException {
        if (resourcePackExists()) {
            deleteDirectory(resourcePackPath);
            XTranslatorMod.LOGGER.info("Deleted existing resource pack");
        }
    }

    /**
     * Recursively deletes a directory.
     */
    private void deleteDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            Files.walk(directory)
                    .sorted((a, b) -> -a.compareTo(b)) // Delete files before directories
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            XTranslatorMod.LOGGER.error("Failed to delete: {}", path, e);
                        }
                    });
        }
    }

    /**
     * Gets the resource pack directory path.
     */
    public Path getResourcePackPath() {
        return resourcePackPath;
    }
}
