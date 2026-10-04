package com.xtranslator.translation;

import com.xtranslator.XTranslationManager;
import com.xtranslator.XTranslatorMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Universal GUI Font Interceptor:
 * Replaces Minecraft's active font engine to transparently translate ANY text, string,
 * description, or wrapped paragraph rendered by ANY mod on screen in real-time.
 */
public class TranslatingFont extends Font {
    private final Font delegate;

    @SuppressWarnings("unchecked")
    public static TranslatingFont wrap(Font original) {
        if (original instanceof TranslatingFont tf) {
            return tf;
        }
        try {
            Function<ResourceLocation, FontSet> fonts = null;
            boolean filterFishyGlyphs = false;
            for (Field f : Font.class.getDeclaredFields()) {
                f.setAccessible(true);
                if (Function.class.isAssignableFrom(f.getType())) {
                    fonts = (Function<ResourceLocation, FontSet>) f.get(original);
                } else if (f.getType() == boolean.class) {
                    filterFishyGlyphs = f.getBoolean(original);
                }
            }
            if (fonts != null) {
                return new TranslatingFont(fonts, filterFishyGlyphs, original);
            }
        } catch (Throwable t) {
            XTranslatorMod.LOGGER.error("Failed to wrap Minecraft Font: {}", t.getMessage());
        }
        return null;
    }

    private TranslatingFont(Function<ResourceLocation, FontSet> fonts, boolean filterFishyGlyphs, Font delegate) {
        super(fonts, filterFishyGlyphs);
        this.delegate = delegate;
    }

    public static final Map<String, Long> RENDERED_TEXTS = new java.util.concurrent.ConcurrentHashMap<>();

    public static void recordRendered(String text) {
        if (text != null && text.length() >= 2 && text.length() <= 600) {
            RENDERED_TEXTS.put(text, System.currentTimeMillis());
            if (RENDERED_TEXTS.size() > 500) {
                long cutoff = System.currentTimeMillis() - 5000;
                RENDERED_TEXTS.entrySet().removeIf(e -> e.getValue() < cutoff);
                if (RENDERED_TEXTS.size() > 1000) {
                    RENDERED_TEXTS.clear();
                }
            }
        }
    }

    public static String getCachedTranslation(String text) {
        if (text == null || text.length() < 2 || text.length() > 600) return null;
        try {
            XTranslationManager manager = XTranslationManager.getInstance();
            if (manager == null) return null;
            if (manager.isSameLanguage(manager.getSourceLanguage(), manager.getTargetLanguage())) {
                return null;
            }
            TranslationService service = manager.getTranslationService();
            if (service == null) return null;
            Map<String, String> cache = service.getTranslationCache();
            String res = cache.get(text);
            if (res == null) {
                String trimmed = text.trim();
                if (!trimmed.isEmpty() && !trimmed.equals(text)) {
                    res = cache.get(trimmed);
                }
            }
            if (res != null) {
                if (manager.getTargetLanguage().startsWith("vi") && LanguageHelper.hasChineseCharacters(res)) {
                    return null;
                }
                return res;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Calculates smart auto-fit horizontal scale factor to prevent translated text
     * from overflowing original menu buttons, cards, frames, and screen bounds.
     */
    public static float calculateAutoFitScale(int origWidth, int transWidth, float startX) {
        if (transWidth <= 0) return 1.0f;
        if (origWidth <= 0) origWidth = transWidth;

        float scale = 1.0f;

        // 1. If translated text is wider than original layout width
        if (transWidth > origWidth) {
            // Allow 8% tolerance + 4px for natural button/margin padding before scaling
            float threshold = Math.max(origWidth * 1.08f, origWidth + 4);
            if (transWidth > threshold) {
                scale = threshold / (float) transWidth;
            }
        }

        // 2. Ensure text never overflows the right edge of the screen
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getWindow() != null) {
                int screenWidth = mc.getWindow().getGuiScaledWidth();
                if (screenWidth > 0 && startX + transWidth * scale > screenWidth - 4) {
                    float maxAllowed = (screenWidth - 4) - startX;
                    if (maxAllowed > 10) {
                        float screenScale = maxAllowed / (float) transWidth;
                        scale = Math.min(scale, screenScale);
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Minimum scale floor: 0.65 (65% width) to ensure text remains crisp and readable
        return Math.clamp(scale, 0.65f, 1.0f);
    }

    @Override
    public int drawInBatch(String text, float x, float y, int color, boolean dropShadow, Matrix4f matrix, MultiBufferSource bufferSource, DisplayMode displayMode, int backgroundColor, int packedLightCoords) {
        if (text == null) return (int) x;
        recordRendered(text);
        String translated = getCachedTranslation(text);
        if (translated != null && !translated.equals(text)) {
            int origWidth = super.width(text);
            int transWidth = super.width(translated);
            float scale = calculateAutoFitScale(origWidth, transWidth, x);
            if (scale < 0.99f) {
                Matrix4f scaled = new Matrix4f(matrix);
                scaled.translate(x, y, 0);
                scaled.scale(scale, 1.0f, 1.0f);
                scaled.translate(-x, -y, 0);
                super.drawInBatch(translated, x, y, color, dropShadow, scaled, bufferSource, displayMode, backgroundColor, packedLightCoords);
                return (int) (x + transWidth * scale);
            }
            return super.drawInBatch(translated, x, y, color, dropShadow, matrix, bufferSource, displayMode, backgroundColor, packedLightCoords);
        }
        return super.drawInBatch(text, x, y, color, dropShadow, matrix, bufferSource, displayMode, backgroundColor, packedLightCoords);
    }

    @Override
    public int drawInBatch(Component text, float x, float y, int color, boolean dropShadow, Matrix4f matrix, MultiBufferSource bufferSource, DisplayMode displayMode, int backgroundColor, int packedLightCoords) {
        if (text != null) {
            String raw = text.getString();
            recordRendered(raw);
            String translated = getCachedTranslation(raw);

            String drawText = translated != null ? translated : raw;
            int origWidth = -1;

            if (translated != null && !translated.equals(raw)) {
                origWidth = super.width(raw);
            } else if (text.getContents() instanceof TranslatableContents translatable) {
                try {
                    XTranslationManager manager = XTranslationManager.getInstance();
                    if (manager != null) {
                        String source = manager.getSourceTextFor(translatable.getKey());
                        if (source != null && !source.isBlank() && !source.equals(raw)) {
                            origWidth = super.width(source);
                        }
                    }
                } catch (Throwable ignored) {}
            }

            int transWidth = super.width(drawText);
            if (origWidth > 0 && transWidth > origWidth) {
                float scale = calculateAutoFitScale(origWidth, transWidth, x);
                if (scale < 0.99f) {
                    Matrix4f scaled = new Matrix4f(matrix);
                    scaled.translate(x, y, 0);
                    scaled.scale(scale, 1.0f, 1.0f);
                    scaled.translate(-x, -y, 0);
                    Component comp = translated != null ? Component.literal(translated).withStyle(text.getStyle()) : text;
                    super.drawInBatch(comp, x, y, color, dropShadow, scaled, bufferSource, displayMode, backgroundColor, packedLightCoords);
                    return (int) (x + transWidth * scale);
                }
            }

            if (translated != null) {
                Component comp = Component.literal(translated).withStyle(text.getStyle());
                return super.drawInBatch(comp, x, y, color, dropShadow, matrix, bufferSource, displayMode, backgroundColor, packedLightCoords);
            }
        }
        return super.drawInBatch(text, x, y, color, dropShadow, matrix, bufferSource, displayMode, backgroundColor, packedLightCoords);
    }

    @Override
    public int drawInBatch(net.minecraft.util.FormattedCharSequence processor, float x, float y, int color, boolean dropShadow, Matrix4f matrix, MultiBufferSource bufferSource, DisplayMode displayMode, int backgroundColor, int packedLightCoords) {
        if (processor != null) {
            StringBuilder sb = new StringBuilder();
            processor.accept((index, style, cp) -> {
                sb.appendCodePoint(cp);
                return true;
            });
            String raw = sb.toString();
            recordRendered(raw);
            String translated = getCachedTranslation(raw);
            if (translated != null && !translated.equals(raw)) {
                int origWidth = super.width(raw);
                int transWidth = super.width(translated);
                float scale = calculateAutoFitScale(origWidth, transWidth, x);
                if (scale < 0.99f) {
                    Matrix4f scaled = new Matrix4f(matrix);
                    scaled.translate(x, y, 0);
                    scaled.scale(scale, 1.0f, 1.0f);
                    scaled.translate(-x, -y, 0);
                    super.drawInBatch(Component.literal(translated).getVisualOrderText(), x, y, color, dropShadow, scaled, bufferSource, displayMode, backgroundColor, packedLightCoords);
                    return (int) (x + transWidth * scale);
                }
                return super.drawInBatch(Component.literal(translated).getVisualOrderText(), x, y, color, dropShadow, matrix, bufferSource, displayMode, backgroundColor, packedLightCoords);
            }
        }
        return super.drawInBatch(processor, x, y, color, dropShadow, matrix, bufferSource, displayMode, backgroundColor, packedLightCoords);
    }

    @Override
    public List<FormattedCharSequence> split(FormattedText text, int maxWidth) {
        if (text != null) {
            String raw = text.getString();
            recordRendered(raw);
            String translated = getCachedTranslation(raw);
            if (translated != null) {
                Style style = text instanceof Component c ? c.getStyle() : Style.EMPTY;
                return super.split(FormattedText.of(translated, style), maxWidth);
            }
        }
        return super.split(text, maxWidth);
    }

    @Override
    public int width(String text) {
        if (text == null) return 0;
        String translated = getCachedTranslation(text);
        if (translated != null && !translated.equals(text)) {
            int origWidth = super.width(text);
            int transWidth = super.width(translated);
            float scale = calculateAutoFitScale(origWidth, transWidth, 0);
            return (int) (transWidth * scale);
        }
        return super.width(text);
    }

    @Override
    public int width(FormattedText text) {
        if (text != null) {
            String raw = text.getString();
            String translated = getCachedTranslation(raw);
            if (translated != null && !translated.equals(raw)) {
                int origWidth = super.width(raw);
                int transWidth = super.width(translated);
                float scale = calculateAutoFitScale(origWidth, transWidth, 0);
                return (int) (transWidth * scale);
            }
            if (text instanceof Component c && c.getContents() instanceof TranslatableContents translatable) {
                try {
                    XTranslationManager manager = XTranslationManager.getInstance();
                    if (manager != null) {
                        String source = manager.getSourceTextFor(translatable.getKey());
                        if (source != null && !source.isBlank() && !source.equals(raw)) {
                            int origWidth = super.width(source);
                            int transWidth = super.width(raw);
                            float scale = calculateAutoFitScale(origWidth, transWidth, 0);
                            return (int) (transWidth * scale);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        return super.width(text);
    }
}
