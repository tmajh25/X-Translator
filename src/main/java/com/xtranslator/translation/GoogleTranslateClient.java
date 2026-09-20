package com.xtranslator.translation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xtranslator.XTranslatorMod;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/**
 * Client for Google Translate (unofficial API) with fallback to MyMemory Translate API
 * and format protection for Minecraft formatting and placeholders.
 */
public class GoogleTranslateClient {
    private static final String GOOGLE_API_URL = "https://translate.googleapis.com/translate_a/single";
    private static final String MYMEMORY_API_URL = "https://api.mymemory.translated.net/get";
    private static final int TIMEOUT_SECONDS = 6;

    private final HttpClient httpClient;
    private final String sourceLanguage;
    private final String targetLanguage;

    public GoogleTranslateClient(String sourceLanguage, String targetLanguage) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.sourceLanguage = normalizeLangCode(sourceLanguage);
        this.targetLanguage = normalizeLangCode(targetLanguage);

        XTranslatorMod.LOGGER.info("Initialized Translation client: {} -> {}", this.sourceLanguage, this.targetLanguage);
    }

    private static String normalizeLangCode(String lang) {
        if (lang == null || lang.isEmpty() || lang.equalsIgnoreCase("auto")) return "auto";
        // Extract 2-letter ISO code if provided as "vi_vn" -> "vi"
        String[] parts = lang.toLowerCase().split("[_-]");
        return parts[0];
    }

    /**
     * Translates a single text string with formatting protection.
     *
     * @param text Text to translate
     * @return Translated text
     */
    public String translate(String text) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            return text;
        }

        // 1. Protect Minecraft format codes and placeholders
        FormatProtector.ProtectedResult protectedResult = FormatProtector.protect(text);
        String toTranslate = protectedResult.getProtectedText();

        String translated = null;
        try {
            // 2. Try Google Translate first (gtx client)
            translated = translateWithGoogle(toTranslate);
        } catch (Exception e) {
            XTranslatorMod.LOGGER.warn("Google Translate failed ({}), trying MyMemory", e.getMessage());
            try {
                // 3. Fallback to MyMemory API
                translated = translateWithMyMemory(toTranslate);
            } catch (Exception ex) {
                XTranslatorMod.LOGGER.error("Both Google and MyMemory translation failed for '{}': {}", text, ex.getMessage());
                return null; // Return null so caller knows it was not translated!
            }
        }

        if (translated == null || translated.isBlank()) {
            return null;
        }

        // 4. Restore protected formatting codes
        return protectedResult.restore(translated);
    }

    private String translateWithGoogle(String text) throws Exception {
        String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8);
        try {
            return requestGoogle(encodedText, "gtx");
        } catch (Exception e) {
            return requestGoogle(encodedText, "dict-chrome-ex");
        }
    }

    private static final String BATCH_DELIMITER = "\n⟦DIV⟧\n";

    private String requestGoogle(String encodedText, String clientId) throws Exception {
        String url = String.format(
            "%s?client=%s&sl=%s&tl=%s&dt=t",
            GOOGLE_API_URL,
            clientId,
            sourceLanguage,
            targetLanguage
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Accept", "*/*")
                .POST(HttpRequest.BodyPublishers.ofString("q=" + encodedText, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 429) {
            Thread.sleep(600); // Back off and retry once
            HttpResponse<String> retry = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (retry.statusCode() == 200 && !retry.body().contains("<HTML>")) {
                response = retry;
            } else {
                throw new Exception("Google Translate HTTP 429");
            }
        }

        if (response.statusCode() != 200) {
            // If POST fails, fallback to GET for smaller payloads
            if (encodedText.length() < 1800) {
                String getUrl = url + "&q=" + encodedText;
                HttpRequest getRequest = HttpRequest.newBuilder()
                        .uri(URI.create(getUrl))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                        .header("Accept", "*/*")
                        .GET()
                        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                        .build();
                HttpResponse<String> getResponse = httpClient.send(getRequest, HttpResponse.BodyHandlers.ofString());
                if (getResponse.statusCode() == 200 && !getResponse.body().contains("<HTML>")) {
                    response = getResponse;
                } else {
                    throw new Exception("Google Translate HTTP " + response.statusCode());
                }
            } else {
                throw new Exception("Google Translate HTTP " + response.statusCode());
            }
        }

        String body = response.body();
        if (body.contains("<HTML>") || body.contains("<html>") || body.contains("Sorry...")) {
            throw new Exception("Google Translate returned HTML/Captcha challenge");
        }

        JsonArray rootArray = JsonParser.parseString(body).getAsJsonArray();
        JsonArray translationsArray = rootArray.get(0).getAsJsonArray();

        StringBuilder translatedText = new StringBuilder();
        for (int i = 0; i < translationsArray.size(); i++) {
            JsonArray translationPart = translationsArray.get(i).getAsJsonArray();
            if (!translationPart.isEmpty() && !translationPart.get(0).isJsonNull()) {
                translatedText.append(translationPart.get(0).getAsString());
            }
        }

        return translatedText.toString();
    }

    private String translateWithMyMemory(String text) throws Exception {
        String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8);
        String src = "auto".equalsIgnoreCase(sourceLanguage) ? "autodetect" : sourceLanguage;
        String langPair = URLEncoder.encode(src + "|" + targetLanguage, StandardCharsets.UTF_8);
        String url = String.format("%s?q=%s&langpair=%s", MYMEMORY_API_URL, encodedText, langPair);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 429) {
            throw new Exception("MyMemory rate limit exceeded");
        }

        if (response.statusCode() != 200) {
            throw new Exception("MyMemory HTTP " + response.statusCode());
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        int responseStatus = json.get("responseStatus").getAsInt();

        if (responseStatus == 200) {
            String val = json.getAsJsonObject("responseData").get("translatedText").getAsString();
            if (val.contains("MYMEMORY WARNING")) {
                throw new Exception("MyMemory daily limit exceeded");
            }
            return val;
        }

        throw new Exception("Invalid response structure from MyMemory");
    }

    /**
     * Translates multiple texts in parallel with fast chunked batching and progress updates.
     */
    public List<String> translateBatch(List<String> texts, IntConsumer onItemDone) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        int size = texts.size();
        String[] results = new String[size];

        // 1. Group texts into chunks (up to 20 items or ~2000 chars) for single HTTP request translation
        List<List<Integer>> chunks = new ArrayList<>();
        List<Integer> currentChunk = new ArrayList<>();
        int currentLength = 0;

        for (int i = 0; i < size; i++) {
            String t = texts.get(i);
            int len = (t != null ? t.length() : 0) + 10;
            if (!currentChunk.isEmpty() && (currentChunk.size() >= 20 || currentLength + len > 2000)) {
                chunks.add(currentChunk);
                currentChunk = new ArrayList<>();
                currentLength = 0;
            }
            currentChunk.add(i);
            currentLength += len;
        }
        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk);
        }

        int threads = Math.min(chunks.size(), 4);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>(chunks.size());

            for (List<Integer> chunkIndices : chunks) {
                futures.add(CompletableFuture.runAsync(() -> {
                    processChunk(texts, chunkIndices, results, onItemDone);
                }, pool));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
        }

        return Arrays.asList(results);
    }

    private void processChunk(List<String> texts, List<Integer> indices, String[] results, IntConsumer onItemDone) {
        if (indices.size() == 1) {
            int idx = indices.get(0);
            try {
                results[idx] = translate(texts.get(idx));
            } catch (Exception e) {
                results[idx] = null;
            } finally {
                if (onItemDone != null) onItemDone.accept(1);
            }
            return;
        }

        // Attempt combined batch request using delimiter
        boolean success = false;
        try {
            List<FormatProtector.ProtectedResult> protectedList = new ArrayList<>(indices.size());
            StringBuilder combined = new StringBuilder();
            for (int i = 0; i < indices.size(); i++) {
                int idx = indices.get(i);
                String raw = texts.get(idx);
                FormatProtector.ProtectedResult pr = FormatProtector.protect(raw);
                protectedList.add(pr);
                if (i > 0) combined.append(BATCH_DELIMITER);
                combined.append(pr.getProtectedText());
            }

            String translatedCombined = translateWithGoogle(combined.toString());
            if (translatedCombined != null && !translatedCombined.isBlank()) {
                String[] parts = translatedCombined.split("\\s*⟦\\s*DIV\\s*⟧\\s*");
                if (parts.length == indices.size()) {
                    for (int i = 0; i < indices.size(); i++) {
                        int idx = indices.get(i);
                        results[idx] = protectedList.get(i).restore(parts[i].trim());
                        if (onItemDone != null) onItemDone.accept(1);
                    }
                    success = true;
                }
            }
        } catch (Exception e) {
            XTranslatorMod.LOGGER.debug("Chunk translation failed, falling back to individual: {}", e.getMessage());
        }

        // Fallback: Translate individually if chunk splitting failed
        if (!success) {
            for (int idx : indices) {
                try {
                    results[idx] = translate(texts.get(idx));
                } catch (Exception e) {
                    results[idx] = null;
                } finally {
                    if (onItemDone != null) onItemDone.accept(1);
                }
            }
        }
    }

    /**
     * Translates multiple texts in parallel.
     */
    public List<String> translateBatch(List<String> texts) {
        return translateBatch(texts, null);
    }

    public boolean isAvailable() {
        try {
            String test = translate("test");
            return test != null && !test.isEmpty();
        } catch (Exception e) {
            XTranslatorMod.LOGGER.error("Translation service check failed", e);
            return false;
        }
    }
}
