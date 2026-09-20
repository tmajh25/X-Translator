package com.xtranslator.translation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xtranslator.XTranslatorMod;

import javax.net.ssl.SSLContext;
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

    private static volatile long GLOBAL_COOLDOWN_UNTIL = 0;
    private static final Object RATE_LOCK = new Object();
    private static long lastRequestTime = 0;
    private static final long MIN_REQUEST_INTERVAL_MS = 250;

    public static boolean isInCooldown() {
        return System.currentTimeMillis() < GLOBAL_COOLDOWN_UNTIL;
    }

    public static long getRemainingCooldownSeconds() {
        long remaining = (GLOBAL_COOLDOWN_UNTIL - System.currentTimeMillis()) / 1000;
        return Math.max(0, remaining);
    }

    public static void triggerCooldown(long durationMs, String reason) {
        long newUntil = System.currentTimeMillis() + durationMs;
        if (newUntil > GLOBAL_COOLDOWN_UNTIL) {
            GLOBAL_COOLDOWN_UNTIL = newUntil;
            XTranslatorMod.LOGGER.warn("Translation cooldown triggered: {} (Paused for {}s to prevent API bans)", reason, durationMs / 1000);
        }
    }

    private static void enforceRateLimit() {
        synchronized (RATE_LOCK) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRequestTime;
            if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                try {
                    Thread.sleep(MIN_REQUEST_INTERVAL_MS - elapsed);
                } catch (InterruptedException ignored) {}
            }
            lastRequestTime = System.currentTimeMillis();
        }
    }

    private final HttpClient httpClient;
    private final String sourceLanguage;
    private final String targetLanguage;

    public GoogleTranslateClient(String sourceLanguage, String targetLanguage) {
        SSLContext sslContext;
        try {
            sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, null, null);
        } catch (Exception e) {
            try {
                sslContext = SSLContext.getDefault();
            } catch (Exception ex) {
                sslContext = null;
            }
        }

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        this.httpClient = builder.build();

        this.sourceLanguage = normalizeLangCode(sourceLanguage);
        this.targetLanguage = normalizeLangCode(targetLanguage);

        XTranslatorMod.LOGGER.info("Initialized Translation client (TLS 1.2): {} -> {}", this.sourceLanguage, this.targetLanguage);
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

        if (isInCooldown()) {
            XTranslatorMod.LOGGER.debug("Skipping translation for '{}': API in cooldown ({}s left)", text, getRemainingCooldownSeconds());
            return null;
        }

        // 1. Protect Minecraft format codes and placeholders
        FormatProtector.ProtectedResult protectedResult = FormatProtector.protect(text);
        String toTranslate = protectedResult.getProtectedText();

        String translated = null;
        try {
            // 2. Try Google Translate first (gtx client)
            translated = translateWithGoogle(toTranslate);
        } catch (Exception e) {
            if (isInCooldown()) {
                return null;
            }
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
        Exception lastException = null;
        // Try gtx client first, then dict-chrome-ex as fallback
        for (String clientId : new String[]{"gtx", "dict-chrome-ex"}) {
            try {
                return requestGoogle(encodedText, clientId);
            } catch (Exception e) {
                lastException = e;
                XTranslatorMod.LOGGER.debug("Google Translate client '{}' failed: {}", clientId, e.getMessage());
            }
        }
        throw lastException != null ? lastException : new Exception("All Google Translate clients failed");
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

        enforceRateLimit();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Handle rate limiting with cooldown
        if (response.statusCode() == 429) {
            triggerCooldown(90_000, "Google Translate HTTP 429 (Rate Limited)");
            throw new Exception("Google Translate HTTP 429 (Rate Limited) — cooldown activated for 90s");
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
                enforceRateLimit();
                HttpResponse<String> getResponse = httpClient.send(getRequest, HttpResponse.BodyHandlers.ofString());
                if (getResponse.statusCode() == 429) {
                    triggerCooldown(90_000, "Google Translate HTTP 429 (Rate Limited)");
                    throw new Exception("Google Translate HTTP 429 (Rate Limited) — cooldown activated for 90s");
                }
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
        if (body == null || body.isBlank()) {
            throw new Exception("Google Translate returned empty response");
        }
        if (body.contains("<HTML>") || body.contains("<html>") || body.contains("Sorry...")) {
            triggerCooldown(90_000, "Google Translate Captcha/HTML challenge");
            throw new Exception("Google Translate returned HTML/Captcha challenge");
        }

        try {
            JsonArray rootArray = JsonParser.parseString(body).getAsJsonArray();
            if (rootArray.isEmpty() || rootArray.get(0).isJsonNull()) {
                throw new Exception("Google Translate returned null translation array");
            }
            JsonArray translationsArray = rootArray.get(0).getAsJsonArray();

            StringBuilder translatedText = new StringBuilder();
            for (int i = 0; i < translationsArray.size(); i++) {
                JsonArray translationPart = translationsArray.get(i).getAsJsonArray();
                if (!translationPart.isEmpty() && !translationPart.get(0).isJsonNull()) {
                    translatedText.append(translationPart.get(0).getAsString());
                }
            }

            String result = translatedText.toString();
            if (result.isBlank()) {
                throw new Exception("Google Translate returned blank translation");
            }
            return result;
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new Exception("Google Translate returned invalid JSON: " + e.getMessage());
        }
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

        enforceRateLimit();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 429) {
            triggerCooldown(90_000, "MyMemory rate limit exceeded");
            throw new Exception("MyMemory rate limit exceeded — cooldown activated for 90s");
        }

        if (response.statusCode() != 200) {
            throw new Exception("MyMemory HTTP " + response.statusCode());
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        int responseStatus = json.get("responseStatus").getAsInt();

        if (responseStatus == 200) {
            String val = json.getAsJsonObject("responseData").get("translatedText").getAsString();
            if (val.contains("MYMEMORY WARNING")) {
                triggerCooldown(120_000, "MyMemory daily limit reached");
                throw new Exception("MyMemory daily limit exceeded — cooldown activated for 120s");
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

        if (isInCooldown()) {
            XTranslatorMod.LOGGER.warn("Skipping batch translation: API in cooldown ({}s remaining)", getRemainingCooldownSeconds());
            return Arrays.asList(results);
        }

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

        int threads = Math.min(chunks.size(), 2);
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
                    if (onItemDone != null && results[idx] != null) onItemDone.accept(1);
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
