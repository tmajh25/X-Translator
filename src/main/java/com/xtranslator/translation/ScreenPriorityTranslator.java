package com.xtranslator.translation;

import com.xtranslator.XTranslationManager;
import com.xtranslator.XTranslatorMod;
import com.xtranslator.chat.TranslationProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforgespi.language.IModInfo;
import org.lwjgl.glfw.GLFW;

import net.minecraft.util.FormattedCharSequence;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real-time Screen Scanner & In-Memory Translator:
 * 1. Auto-detects the mod owning the active GUI screen (e.g. Tensura's Ability Archive).
 * 2. Deeply inspects screen titles, widgets, renderables, scroll lists, and container items.
 * 3. Supports 'V' hotkey to force-translate all menu texts and automatically re-inits the screen!
 */
@EventBusSubscriber(modid = XTranslatorMod.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class ScreenPriorityTranslator {

    private static final ExecutorService PRIORITY_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "XTranslator-ScreenQueue");
        t.setDaemon(true);
        return t;
    });

    private static final Set<String> PENDING_KEYS = ConcurrentHashMap.newKeySet();
    private static long lastScreenScan = 0;

    public static volatile Screen lastOpenedScreen = null;
    public static volatile String lastOpenedModId = null;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            net.minecraft.locale.Language current = net.minecraft.locale.Language.getInstance();
            if (!(current instanceof DynamicLanguageWrapper)) {
                net.minecraft.locale.Language.inject(new DynamicLanguageWrapper(current));
                XTranslatorMod.LOGGER.info("XTranslator DynamicLanguageWrapper successfully injected into Minecraft Language!");
            }

            if (mc.font != null && !(mc.font instanceof TranslatingFont)) {
                TranslatingFont tf = TranslatingFont.wrap(mc.font);
                if (tf != null) {
                    try {
                        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                        unsafeField.setAccessible(true);
                        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
                        java.lang.reflect.Field fontField = Minecraft.class.getDeclaredField("font");
                        long offset = unsafe.objectFieldOffset(fontField);
                        unsafe.putObject(mc, offset, tf);
                        XTranslatorMod.LOGGER.info("XTranslator TranslatingFont successfully injected into Minecraft.font!");
                    } catch (Throwable t) {
                        XTranslatorMod.LOGGER.debug("Could not inject Minecraft.font via Unsafe: {}", t.getMessage());
                    }
                }
            }
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean IS_SCREEN_TRANSLATING = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile long TRANSLATING_START_TIME = 0;

    /**
     * Records active screen and injects translating font.
     */
    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        if (screen != null && !(screen instanceof net.minecraft.client.gui.screens.ChatScreen)) {
            lastOpenedScreen = screen;
            String modId = detectModId(screen);
            if (modId != null) {
                lastOpenedModId = modId;
            }
            injectFontIntoScreen(screen);
        }
    }

    public static void injectFontIntoScreen(Screen screen) {
        if (screen == null) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.font instanceof TranslatingFont tf) {
                for (java.lang.reflect.Field f : Screen.class.getDeclaredFields()) {
                    if (net.minecraft.client.gui.Font.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        if (f.get(screen) != tf) {
                            f.set(screen, tf);
                        }
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Hotkey 'V': Press 'V' inside any screen to immediately force-translate all text on the screen!
     */
    @SubscribeEvent
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (event.getKeyCode() == GLFW.GLFW_KEY_V && !isTyping(event.getScreen())) {
            Screen screen = event.getScreen();
            if (screen != null) {
                scanAndTranslateScreen(screen, true);
                event.setCanceled(true);
            }
        }
    }

    private static boolean isTyping(Screen screen) {
        if (screen == null) return false;
        GuiEventListener focused = screen.getFocused();
        return focused instanceof net.minecraft.client.gui.components.EditBox;
    }

    /**
     * Detects which mod an open screen belongs to by inspecting its class package, title, and mod lists.
     */
    public static String detectModId(Screen screen) {
        if (screen == null) return null;
        String className = screen.getClass().getName().toLowerCase();

        for (IModInfo mod : ModList.get().getMods()) {
            String id = mod.getModId().toLowerCase();
            if (id.equals("minecraft") || id.equals("neoforge") || id.equals("xtranslator")) continue;
            if (className.contains("." + id + ".") || className.contains("." + id.replace("_", "") + ".") || className.contains(id)) {
                return mod.getModId();
            }
        }

        // Check screen title for namespace
        Component title = screen.getTitle();
        if (title != null && title.getContents() instanceof TranslatableContents tc) {
            String key = tc.getKey();
            String ns = extractNamespaceFromKey(key);
            if (ns != null && !ns.equals("minecraft")) return ns;
        }

        return null;
    }

    private static String extractNamespaceFromKey(String key) {
        if (key == null || !key.contains(".")) return null;
        String[] parts = key.split("\\.");
        if (parts.length >= 2) {
            if (parts[0].equals("item") || parts[0].equals("block") || parts[0].equals("entity") || parts[0].equals("gui") || parts[0].equals("skill")) {
                return parts[1];
            }
            return parts[0];
        }
        return null;
    }

    public static class ScanCollector {
        public final Map<String, String> toTranslate = new LinkedHashMap<>();
        public final Map<String, String> alreadyCached = new LinkedHashMap<>();
        public final Set<String> skipped = new LinkedHashSet<>();
    }

    private static void logScreenScan(Screen screen, String modId, String targetLang,
                                      Map<String, String> toTranslate,
                                      Map<String, String> alreadyCached,
                                      Set<String> skipped) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.gameDirectory == null) return;
            Path logDir = mc.gameDirectory.toPath().resolve("xtranslator").resolve("logs");
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve("screen_translation.log");

            String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String screenTitle = screen.getTitle() != null ? screen.getTitle().getString() : "";
            String screenClass = screen.getClass().getName();

            StringBuilder sb = new StringBuilder();
            sb.append("================================================================================\n");
            sb.append(String.format("[%s] BẮT ĐẦU QUÉT TAB / SCREEN (Phím V)\n", timeStr));
            sb.append(String.format("- Màn hình: %s (Tiêu đề: '%s')\n", screenClass, screenTitle));
            sb.append(String.format("- Mod: %s | Ngôn ngữ đích: %s\n", (modId != null ? modId : "Chung"), targetLang));
            sb.append(String.format("- Thống kê: %d đoạn CẦN DỊCH, %d đoạn ĐÃ CÓ BẢN DỊCH/CACHE, %d đoạn BỎ QUA/TIẾNG VIỆT\n",
                    toTranslate.size(), alreadyCached.size(), skipped.size()));
            sb.append("--------------------------------------------------------------------------------\n");

            sb.append(String.format(">>> DANH SÁCH %d ĐOẠN ĐANG ĐƯỢC GỬI ĐI DỊCH MỚI:\n", toTranslate.size()));
            if (toTranslate.isEmpty()) {
                sb.append("    (Không có đoạn nào cần dịch mới)\n");
            } else {
                int idx = 1;
                for (Map.Entry<String, String> e : toTranslate.entrySet()) {
                    sb.append(String.format("    [%d] \"%s\"\n", idx++, e.getValue()));
                }
            }

            sb.append(String.format("\n>>> DANH SÁCH %d ĐOẠN ĐÃ CÓ BẢN DỊCH SẴN (CACHE / PACK):\n", alreadyCached.size()));
            if (alreadyCached.isEmpty()) {
                sb.append("    (Trống)\n");
            } else {
                int idx = 1;
                for (Map.Entry<String, String> e : alreadyCached.entrySet()) {
                    sb.append(String.format("    [%d] \"%s\" ➔ \"%s\"\n", idx++, e.getKey(), e.getValue()));
                }
            }

            if (!skipped.isEmpty()) {
                sb.append(String.format("\n>>> DANH SÁCH %d ĐOẠN ĐÃ LÀ TIẾNG VIỆT HOẶC KÝ TỰ BỎ QUA:\n", skipped.size()));
                int idx = 1;
                for (String s : skipped) {
                    sb.append(String.format("    [%d] \"%s\"\n", idx++, s));
                    if (idx > 50) {
                        sb.append(String.format("    ... và %d đoạn khác\n", skipped.size() - 50));
                        break;
                    }
                }
            }

            sb.append("================================================================================\n\n");

            Files.writeString(logFile, sb.toString(),
                    java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);

            XTranslatorMod.LOGGER.info("Screen scan logged to: {}", logFile.toAbsolutePath());
        } catch (Throwable t) {
            XTranslatorMod.LOGGER.warn("Could not write screen_translation.log: {}", t.getMessage());
        }
    }

    private static void logTranslationResults(Map<String, String> translated, long elapsedMs) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.gameDirectory == null) return;
            Path logFile = mc.gameDirectory.toPath().resolve("xtranslator").resolve("logs").resolve("screen_translation.log");

            String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[%s] KẾT QUẢ DỊCH XONG TRONG %d ms (Thành công: %d đoạn):\n", timeStr, elapsedMs, translated.size()));
            int idx = 1;
            for (Map.Entry<String, String> e : translated.entrySet()) {
                sb.append(String.format("    [%d] Gốc: \"%s\"\n        Dịch: \"%s\"\n", idx++, e.getKey(), e.getValue()));
            }
            sb.append("--------------------------------------------------------------------------------\n\n");

            Files.writeString(logFile, sb.toString(),
                    java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Deeply scans the screen and translates all missing texts in memory.
     */
    public static void scanAndTranslateScreen(Screen screen, boolean force) {
        XTranslationManager manager;
        try {
            manager = XTranslationManager.getInstance();
        } catch (Exception e) {
            return;
        }

        TranslationService service = manager.getTranslationService();
        if (service == null) return;

        Map<String, String> cache = service.getTranslationCache();
        String modId = detectModId(screen);
        String targetLang = manager.getTargetLanguage();
        ScanCollector collector = new ScanCollector();

        // 1. Scan texts physically rendered on this active tab/screen by TranslatingFont!
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : TranslatingFont.RENDERED_TEXTS.entrySet()) {
            if (now - entry.getValue() < 3000) { // Texts drawn within the last 3 seconds
                extractLiteral(entry.getKey(), collector, cache, targetLang);
            }
        }

        // 2. Scan Screen Title
        extractComponent(screen.getTitle(), collector, cache, manager);

        // 3. Scan Widgets and Children
        for (GuiEventListener child : screen.children()) {
            inspectGuiElement(child, collector, cache, manager, 0);
        }

        // 4. Scan Renderables (Buttons, sliders, custom widgets) via reflection
        try {
            Field renderablesField = null;
            Class<?> sc = Screen.class;
            for (Field f : sc.getDeclaredFields()) {
                if (List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object listObj = f.get(screen);
                    if (listObj instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Renderable) {
                        renderablesField = f;
                        break;
                    }
                }
            }
            if (renderablesField != null) {
                List<?> list = (List<?>) renderablesField.get(screen);
                for (Object item : list) {
                    inspectGuiElement(item, collector, cache, manager, 0);
                }
            }
        } catch (Throwable ignored) {
        }

        // 5. If Container Screen, scan slots & container menu
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            try {
                AbstractContainerMenu menu = containerScreen.getMenu();
                if (menu != null) {
                    for (Slot slot : menu.slots) {
                        ItemStack stack = slot.getItem();
                        if (stack != null && !stack.isEmpty()) {
                            extractComponent(stack.getHoverName(), collector, cache, manager);
                        }
                    }
                    deepScanObject(menu, collector, cache, manager, 0, new HashSet<>());
                }
            } catch (Throwable ignored) {
            }
        }

        // 6. Deep reflection scan on Screen fields (Custom RPG skill lists/data)
        deepScanObject(screen, collector, cache, manager, 0, new HashSet<>());

        // Log scan details to disk file only when player presses V (force scan)
        final String displayName = modId != null ? modId : screen.getClass().getSimpleName();
        if (force) {
            logScreenScan(screen, modId, targetLang, collector.toTranslate, collector.alreadyCached, collector.skipped);
        }

        // Check if anything needs translation
        if (collector.toTranslate.isEmpty()) {
            if (force) {
                TranslationProgress.sendActionBar(Component.translatable("xtranslator.chat.prefix")
                        .append(Component.translatable("xtranslator.chat.screen_clean")));
            }
            return;
        }

        // Concurrency guard with 10s auto-expiry watchdog
        long curTime = System.currentTimeMillis();
        if (IS_SCREEN_TRANSLATING.get() && (curTime - TRANSLATING_START_TIME > 10_000)) {
            IS_SCREEN_TRANSLATING.set(false);
        }

        if (!IS_SCREEN_TRANSLATING.compareAndSet(false, true)) {
            if (force) {
                TranslationProgress.sendActionBar(Component.translatable("xtranslator.chat.prefix")
                        .append(Component.translatable("xtranslator.chat.screen_busy")));
            }
            return;
        }
        TRANSLATING_START_TIME = curTime;

        if (force) {
            TranslationProgress.sendActionBar(Component.translatable("xtranslator.chat.prefix")
                    .append(Component.translatable("xtranslator.chat.screen_start", displayName, collector.toTranslate.size())));
            TranslationProgress.startTranslation(collector.toTranslate.size(), displayName, targetLang);
        }

        // Execute batch translation
        final String currentModId = modId;
        final long startTime = curTime;
        final Map<String, String> queued = collector.toTranslate;
        PRIORITY_EXECUTOR.submit(() -> {
            try {
                Map<String, String> translated = service.translateBatch(queued, force ? TranslationProgress::addProgress : null);
                if (targetLang.startsWith("vi")) {
                    translated.entrySet().removeIf(e -> LanguageHelper.hasChineseCharacters(e.getValue()) || e.getValue().equals(e.getKey()));
                }
                service.putAllTranslations(translated);
                service.saveCache();

                if (currentModId != null && !translated.isEmpty()) {
                    manager.appendTranslationsToResourcePack(currentModId, translated);
                }

                // Refresh the screen on Minecraft's render thread so new translations show up immediately!
                Minecraft.getInstance().execute(() -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.screen == screen) {
                        try {
                            screen.init(mc, screen.width, screen.height);
                        } catch (Throwable ignored) {
                        }
                    }
                });

                long elapsed = System.currentTimeMillis() - startTime;
                logTranslationResults(translated, elapsed);

                if (force) {
                    if (translated.isEmpty()) {
                        TranslationProgress.reset();
                        TranslationProgress.sendActionBar(Component.literal("§e[XTranslator] ")
                                .append(Component.translatable("xtranslator.chat.screen_busy")));
                    } else {
                        TranslationProgress.completeTranslation();
                        TranslationProgress.sendActionBar(Component.translatable("xtranslator.chat.prefix")
                                .append(Component.translatable("xtranslator.chat.screen_done", translated.size())));
                    }
                }
            } catch (Throwable e) {
                XTranslatorMod.LOGGER.error("Failed to translate screen contents", e);
                if (force) {
                    TranslationProgress.reset();
                }
            } finally {
                IS_SCREEN_TRANSLATING.set(false);
            }
        });
    }

    private static void inspectGuiElement(Object element, ScanCollector collector, Map<String, String> cache, XTranslationManager manager, int depth) {
        if (element == null || depth > 2) return;

        if (element instanceof AbstractWidget widget) {
            if (!widget.visible) return; // Skip hidden widgets from other tabs!
            extractComponent(widget.getMessage(), collector, cache, manager);
            try {
                Tooltip tooltip = widget.getTooltip();
                if (tooltip != null) {
                    for (Field f : Tooltip.class.getDeclaredFields()) {
                        if (Component.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            Object tipComp = f.get(tooltip);
                            if (tipComp instanceof Component c) {
                                extractComponent(c, collector, cache, manager);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (element instanceof AbstractSelectionList<?> list) {
            for (Object entry : list.children()) {
                deepScanObject(entry, collector, cache, manager, 0, new HashSet<>());
            }
        }
    }

    private static void deepScanObject(Object obj, ScanCollector collector, Map<String, String> cache, XTranslationManager manager, int depth, Set<Object> visited) {
        if (obj == null || depth > 2 || visited.contains(obj)) return;
        visited.add(obj);

        Class<?> clazz = obj.getClass();
        String className = clazz.getName();
        if (className.startsWith("java.") || className.startsWith("net.minecraft.client.renderer") || className.startsWith("org.lwjgl")) {
            return;
        }

        while (clazz != null && clazz != Object.class && clazz != Screen.class) {
            for (Field field : clazz.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object val = field.get(obj);
                    if (val == null) continue;

                    String targetLang = manager.getTargetLanguage();
                    if (val instanceof Component comp) {
                        extractComponent(comp, collector, cache, manager);
                    } else if (val instanceof FormattedText ft) {
                        extractLiteral(ft.getString(), collector, cache, targetLang);
                    } else if (val instanceof FormattedCharSequence fcs) {
                        StringBuilder sb = new StringBuilder();
                        fcs.accept((index, style, cp) -> {
                            sb.appendCodePoint(cp);
                            return true;
                        });
                        extractLiteral(sb.toString(), collector, cache, targetLang);
                    } else if (val instanceof String str) {
                        extractLiteral(str, collector, cache, targetLang);
                    } else if (val instanceof ItemStack stack) {
                        if (!stack.isEmpty()) {
                            extractComponent(stack.getHoverName(), collector, cache, manager);
                        }
                    } else if (val instanceof Collection<?> col) {
                        for (Object item : col) {
                            if (item instanceof Component comp) {
                                extractComponent(comp, collector, cache, manager);
                            } else if (item instanceof String str) {
                                extractLiteral(str, collector, cache, targetLang);
                            } else if (item instanceof FormattedCharSequence fcs) {
                                StringBuilder sb = new StringBuilder();
                                fcs.accept((index, style, cp) -> {
                                    sb.appendCodePoint(cp);
                                    return true;
                                });
                                extractLiteral(sb.toString(), collector, cache, targetLang);
                            } else if (item != null && depth < 1) {
                                deepScanObject(item, collector, cache, manager, depth + 1, visited);
                            }
                        }
                    } else if (val instanceof Map<?, ?> map) {
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            if (entry.getKey() instanceof String s) extractLiteral(s, collector, cache, targetLang);
                            if (entry.getValue() instanceof Component c) extractComponent(c, collector, cache, manager);
                            else if (entry.getValue() instanceof String s) extractLiteral(s, collector, cache, targetLang);
                        }
                    } else if (val.getClass().isArray() && !val.getClass().getComponentType().isPrimitive()) {
                        Object[] arr = (Object[]) val;
                        for (Object item : arr) {
                            if (item instanceof Component comp) extractComponent(comp, collector, cache, manager);
                            else if (item instanceof String str) extractLiteral(str, collector, cache, targetLang);
                            else if (item != null && depth < 2) deepScanObject(item, collector, cache, manager, depth + 1, visited);
                        }
                    } else if (depth < 2 && !val.getClass().isPrimitive() && !val.getClass().getName().startsWith("java.") && !val.getClass().getName().startsWith("net.minecraft.")) {
                        deepScanObject(val, collector, cache, manager, depth + 1, visited);
                    }
                } catch (Throwable ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    private static void extractComponent(Component comp, ScanCollector collector, Map<String, String> cache, XTranslationManager manager) {
        if (comp == null) return;

        String targetLang = manager.getTargetLanguage();
        String text = comp.getString();
        if (LanguageHelper.isAlreadyInTargetLanguage(text, targetLang)) {
            collector.skipped.add(text);
            return; // Pre-existing target language text on screen, DO NOT TOUCH!
        }

        if (comp.getContents() instanceof TranslatableContents translatable) {
            String key = translatable.getKey();
            if (manager.isAlreadyTranslated(key)) {
                collector.alreadyCached.put(key, manager.getSourceTextFor(key) + " (ResourcePack)");
                return; // Already translated in active pack, DO NOT TOUCH!
            }
            if (cache.containsKey(key)) {
                collector.alreadyCached.put(key, cache.get(key));
                return;
            }
            if (!collector.toTranslate.containsKey(key)) {
                String sourceText = manager.getSourceTextFor(key);
                if (sourceText == null || sourceText.isBlank() || sourceText.equals(key)) {
                    sourceText = translatable.getFallback() != null ? translatable.getFallback() : text;
                }
                if (sourceText != null && !sourceText.isBlank() && !sourceText.equals(key)) {
                    if (LanguageHelper.isAlreadyInTargetLanguage(sourceText, targetLang)) {
                        collector.skipped.add(sourceText);
                    } else {
                        collector.toTranslate.put(key, sourceText);
                    }
                }
            }
        } else {
            extractLiteral(text, collector, cache, targetLang);
        }
    }

    private static final java.util.regex.Pattern METRIC_OR_NUMBER_PATTERN = java.util.regex.Pattern.compile(
        "^\\s*(?:§[0-9a-fk-orA-FK-OR])*[+\\-xX/]?\\s*\\d+(?:[.,]\\d+)?(?:\\s*/\\s*\\d+(?:[.,]\\d+)?)?\\s*(?:%|‰|x|X|°|°C|°F|K|RPM|SU|FE|RF|EU|HE|J|kJ|MJ|GJ|W|kW|MW|GW|mB|MB|B|t|/t|tick|ticks|cps|s|sec|min|h|xp|XP|lvl|Level|HP|Armor|T|m|km|cm|mm|px|blocks?|items?)?(?:/[a-zA-Z]+)?\\s*$",
        java.util.regex.Pattern.CASE_INSENSITIVE
    );

    private static final java.util.regex.Pattern PURE_SYMBOLS_PATTERN = java.util.regex.Pattern.compile(
        "^\\s*(?:§[0-9a-fk-orA-FK-OR]|[-+*/=<>:_|#~^.,()\\[\\]{}!?;\"'\\\\%\\s])+\\s*$"
    );

    public static boolean shouldSkipText(String text) {
        if (text == null) return true;
        String clean = text.replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
        if (clean.length() <= 1) return true;
        if (METRIC_OR_NUMBER_PATTERN.matcher(clean).matches()) return true;
        if (PURE_SYMBOLS_PATTERN.matcher(clean).matches()) return true;
        return false;
    }

    private static void extractLiteral(String text, ScanCollector collector, Map<String, String> cache, String targetLang) {
        if (text == null || text.trim().length() < 2 || text.length() > 1500) return;
        String trimmed = text.trim();
        if (shouldSkipText(trimmed) || LanguageHelper.isAlreadyInTargetLanguage(trimmed, targetLang)) {
            collector.skipped.add(trimmed);
            return;
        }
        if (!trimmed.matches(".*[a-zA-Z]{2,}.*")) {
            collector.skipped.add(trimmed);
            return;
        }
        if (trimmed.matches("\\d+\\s*FPS")) return;
        if (trimmed.startsWith("/") || trimmed.startsWith("http") || trimmed.startsWith("xtranslator.")) return;

        if (cache.containsKey(trimmed)) {
            collector.alreadyCached.put(trimmed, cache.get(trimmed));
        } else if (!collector.toTranslate.containsKey(trimmed)) {
            collector.toTranslate.put(trimmed, trimmed);
        }
    }


    private static final Map<String, Long> FAILED_TOOLTIP_KEYS = new ConcurrentHashMap<>();

    private static void recordTooltipFailure(String key) {
        if (FAILED_TOOLTIP_KEYS.size() > 300) {
            long cutoff = System.currentTimeMillis() - 180_000;
            FAILED_TOOLTIP_KEYS.entrySet().removeIf(e -> e.getValue() < cutoff);
            if (FAILED_TOOLTIP_KEYS.size() > 600) {
                FAILED_TOOLTIP_KEYS.clear();
            }
        }
        FAILED_TOOLTIP_KEYS.put(key, System.currentTimeMillis());
    }

    private static boolean isTooltipFailedRecently(String key) {
        Long time = FAILED_TOOLTIP_KEYS.get(key);
        if (time == null) return false;
        if (System.currentTimeMillis() - time > 180_000) { // 3 minutes cooldown
            FAILED_TOOLTIP_KEYS.remove(key);
            return false;
        }
        return true;
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        XTranslationManager manager;
        try {
            manager = XTranslationManager.getInstance();
        } catch (Exception e) {
            return;
        }

        TranslationService service = manager.getTranslationService();
        if (service == null) return;

        String targetLang = manager.getTargetLanguage();
        Map<String, String> cache = service.getTranslationCache();
        List<Component> tooltip = event.getToolTip();

        for (int i = 0; i < tooltip.size(); i++) {
            Component comp = tooltip.get(i);
            String compStr = comp.getString();
            if (shouldSkipText(compStr) || LanguageHelper.isAlreadyInTargetLanguage(compStr, targetLang)) {
                continue; // Skip numbers, metrics or already translated text!
            }

            if (comp.getContents() instanceof TranslatableContents translatable) {
                String key = translatable.getKey();
                if (manager.isAlreadyTranslated(key)) {
                    continue; // Respect official translation!
                }
                if (cache.containsKey(key)) {
                    if (translatable.getArgs().length > 0) {
                        tooltip.set(i, Component.translatable(key, translatable.getArgs()).withStyle(comp.getStyle()));
                    } else {
                        tooltip.set(i, Component.literal(cache.get(key)).withStyle(comp.getStyle()));
                    }
                } else if (!PENDING_KEYS.contains(key) && !isTooltipFailedRecently(key)) {
                    PENDING_KEYS.add(key);
                    String rawText = manager.getSourceTextFor(key);
                    if (rawText == null || rawText.isBlank() || rawText.equals(key)) {
                        rawText = translatable.getFallback() != null ? translatable.getFallback() : comp.getString();
                        if (rawText == null || rawText.isBlank()) rawText = key;
                    }
                    final String originalText = rawText;
                    PRIORITY_EXECUTOR.submit(() -> {
                        try {
                            String translated = service.translate(key, originalText);
                            if (translated != null && !translated.isBlank() && !translated.equals(originalText)) {
                                manager.addPriorityTranslation(key, translated);
                            } else {
                                recordTooltipFailure(key);
                            }
                        } catch (Exception ex) {
                            recordTooltipFailure(key);
                            XTranslatorMod.LOGGER.debug("Priority tooltip translation error: {}", ex.getMessage());
                        } finally {
                            PENDING_KEYS.remove(key);
                        }
                    });
                }
            } else {
                String text = comp.getString();
                if (text != null && text.length() > 1 && !text.isBlank() && text.length() < 250 && !shouldSkipText(text)) {
                    if (cache.containsKey(text)) {
                        tooltip.set(i, Component.literal(cache.get(text)).withStyle(comp.getStyle()));
                    } else if (!PENDING_KEYS.contains(text) && !isTooltipFailedRecently(text)) {
                        PENDING_KEYS.add(text);
                        PRIORITY_EXECUTOR.submit(() -> {
                            try {
                                String translated = service.translate(text, text);
                                if (translated != null && !translated.isBlank() && !translated.equals(text)) {
                                    manager.addPriorityTranslation(text, translated);
                                } else {
                                    recordTooltipFailure(text);
                                }
                            } catch (Exception ignored) {
                                recordTooltipFailure(text);
                            } finally {
                                PENDING_KEYS.remove(text);
                            }
                        });
                    }
                }
            }
        }
    }
}
