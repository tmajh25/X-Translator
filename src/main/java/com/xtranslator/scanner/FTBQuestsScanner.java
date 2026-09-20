package com.xtranslator.scanner;

import com.xtranslator.XTranslatorMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scanner for FTB Quests SNBT language files.
 *
 * FTB Quests stores translations in SNBT (Stringified NBT) format at:
 * config/ftbquests/quests/lang/en_us.snbt
 */
public class FTBQuestsScanner {
    // Pattern to match SNBT entries: key: "value" or key: ["value1", "value2"]
    private static final Pattern ENTRY_PATTERN = Pattern.compile("^\\s*([^:]+):\\s*(.+)$");
    private static final Pattern STRING_PATTERN = Pattern.compile("\"([^\"]+)\"");

    private final String sourceLanguage;
    private final String targetLanguage;

    public FTBQuestsScanner(String sourceLanguage, String targetLanguage) {
        this.sourceLanguage = sourceLanguage;
        this.targetLanguage = targetLanguage;
    }

    /**
     * Scans FTB Quests language files for missing translations.
     *
     * @param gameDirectory The game directory (typically .minecraft or run/)
     * @return Map of translation key -> original text
     */
    public Map<String, String> scanForMissingTranslations(Path gameDirectory) {
        Map<String, String> missingTranslations = new LinkedHashMap<>();

        // Path to FTB Quests language files
        Path questsLangDir = gameDirectory.resolve("config/ftbquests/quests/lang");

        if (!Files.exists(questsLangDir)) {
            XTranslatorMod.LOGGER.info("FTB Quests language directory not found: {}", questsLangDir);
            return missingTranslations;
        }

        Path sourceFile = resolveSourceFile(questsLangDir);
        Path targetFile = resolveTargetFile(questsLangDir);

        if (!Files.exists(sourceFile)) {
            XTranslatorMod.LOGGER.info("FTB Quests source language file not found: {}", sourceFile);
            return missingTranslations;
        }

        try {
            // Load source language entries
            Map<String, String> sourceEntries = loadSnbtFile(sourceFile);
            XTranslatorMod.LOGGER.info("Found {} FTB Quests entries in {}", sourceEntries.size(), sourceLanguage);

            // Load target language entries if they exist
            Map<String, String> targetEntries = new HashMap<>();
            if (Files.exists(targetFile)) {
                targetEntries = loadSnbtFile(targetFile);
                XTranslatorMod.LOGGER.info("Found {} existing FTB Quests translations in {}", targetEntries.size(), targetLanguage);
            }

            // Find missing translations
            for (Map.Entry<String, String> entry : sourceEntries.entrySet()) {
                String key = entry.getKey();
                if (!targetEntries.containsKey(key)) {
                    missingTranslations.put(key, entry.getValue());
                }
            }

            XTranslatorMod.LOGGER.info("Found {} missing FTB Quests translations", missingTranslations.size());

        } catch (IOException e) {
            XTranslatorMod.LOGGER.error("Failed to scan FTB Quests files", e);
        }

        return missingTranslations;
    }

    /**
     * Loads entries from an SNBT language file.
     *
     * @param file Path to the SNBT file
     * @return Map of key -> value (arrays are joined with newlines)
     */
    private Map<String, String> loadSnbtFile(Path file) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(file);

        for (String line : lines) {
            line = line.trim();

            // Skip empty lines, braces, and comments
            if (line.isEmpty() || line.equals("{") || line.equals("}") || line.startsWith("//")) {
                continue;
            }

            // Parse entry
            Matcher matcher = ENTRY_PATTERN.matcher(line);
            if (matcher.matches()) {
                String key = matcher.group(1).trim();
                String valueStr = matcher.group(2).trim();

                // Extract string value(s)
                List<String> values = new ArrayList<>();
                Matcher stringMatcher = STRING_PATTERN.matcher(valueStr);
                while (stringMatcher.find()) {
                    values.add(stringMatcher.group(1));
                }

                if (!values.isEmpty()) {
                    // Join multiple values with newlines (for arrays)
                    String value = String.join("\n", values);
                    entries.put(key, value);
                }
            }
        }

        return entries;
    }

    /**
     * Writes translated entries to a target SNBT file.
     *
     * @param gameDirectory The game directory
     * @param translations Map of key -> translated value
     */
    public void writeTranslations(Path gameDirectory, Map<String, String> translations) throws IOException {
        if (translations.isEmpty()) {
            XTranslatorMod.LOGGER.info("No FTB Quests translations to write");
            return;
        }

        Path questsLangDir = gameDirectory.resolve("config/ftbquests/quests/lang");
        Files.createDirectories(questsLangDir);

        Path targetFile = resolveTargetFile(questsLangDir);

        // Load existing translations
        Map<String, String> existingTranslations = new LinkedHashMap<>();
        if (Files.exists(targetFile)) {
            existingTranslations = loadSnbtFile(targetFile);
        }

        // Merge new translations with existing ones
        existingTranslations.putAll(translations);

        // Write SNBT file
        StringBuilder snbt = new StringBuilder();
        snbt.append("{\n");

        for (Map.Entry<String, String> entry : existingTranslations.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            // Check if value contains newlines (was an array)
            if (value.contains("\n")) {
                String[] parts = value.split("\n");
                snbt.append("\t").append(key).append(": [");
                for (int i = 0; i < parts.length; i++) {
                    snbt.append("\"").append(escapeString(parts[i])).append("\"");
                    if (i < parts.length - 1) {
                        snbt.append(", ");
                    }
                }
                snbt.append("]\n");
            } else {
                // Single string value
                snbt.append("\t").append(key).append(": \"").append(escapeString(value)).append("\"\n");
            }
        }

        snbt.append("}\n");

        Files.writeString(targetFile, snbt.toString());
        XTranslatorMod.LOGGER.info("Wrote {} FTB Quests translations to {}", existingTranslations.size(), targetFile);
    }

    /**
     * Removes translated quest file.
     *
     * @param gameDirectory The game directory
     * @return true if file was deleted, false otherwise
     */
    public boolean removeTranslations(Path gameDirectory) {
        Path questsLangDir = gameDirectory.resolve("config/ftbquests/quests/lang");
        Path targetFile = resolveTargetFile(questsLangDir);

        if (Files.exists(targetFile)) {
            try {
                Files.delete(targetFile);
                XTranslatorMod.LOGGER.info("Removed FTB Quests translation file: {}", targetFile);
                return true;
            } catch (IOException e) {
                XTranslatorMod.LOGGER.error("Failed to delete FTB Quests translation file", e);
                return false;
            }
        }

        XTranslatorMod.LOGGER.info("FTB Quests translation file not found: {}", targetFile);
        return false;
    }

    /**
     * Checks if quest translations exist.
     *
     * @param gameDirectory The game directory
     * @return true if translation file exists
     */
    public boolean translationsExist(Path gameDirectory) {
        Path questsLangDir = gameDirectory.resolve("config/ftbquests/quests/lang");
        Path targetFile = resolveTargetFile(questsLangDir);
        return Files.exists(targetFile);
    }

    private Path resolveSourceFile(Path questsLangDir) {
        if (sourceLanguage == null || sourceLanguage.isEmpty() || "auto".equalsIgnoreCase(sourceLanguage)) {
            Path enUs = questsLangDir.resolve("en_us.snbt");
            if (Files.exists(enUs)) return enUs;
            Path en = questsLangDir.resolve("en.snbt");
            if (Files.exists(en)) return en;
            return enUs;
        }

        Path direct = questsLangDir.resolve(sourceLanguage + ".snbt");
        if (Files.exists(direct)) return direct;

        Path lower = questsLangDir.resolve(sourceLanguage.toLowerCase() + ".snbt");
        if (Files.exists(lower)) return lower;

        if (sourceLanguage.equalsIgnoreCase("en")) {
            Path enUs = questsLangDir.resolve("en_us.snbt");
            if (Files.exists(enUs)) return enUs;
        }

        if (sourceLanguage.contains("_")) {
            Path shortFile = questsLangDir.resolve(sourceLanguage.split("_")[0] + ".snbt");
            if (Files.exists(shortFile)) return shortFile;
        }

        Path defaultEn = questsLangDir.resolve("en_us.snbt");
        if (Files.exists(defaultEn)) return defaultEn;

        return direct;
    }

    private Path resolveTargetFile(Path questsLangDir) {
        Path direct = questsLangDir.resolve(targetLanguage + ".snbt");
        if (Files.exists(direct)) return direct;

        if (targetLanguage.contains("_")) {
            Path shortFile = questsLangDir.resolve(targetLanguage.split("_")[0] + ".snbt");
            if (Files.exists(shortFile)) return shortFile;
        }

        return direct;
    }

    /**
     * Escapes special characters in SNBT strings.
     */
    private String escapeString(String str) {
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
