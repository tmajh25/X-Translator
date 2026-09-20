package com.xtranslator.translation;

import com.xtranslator.XTranslationManager;
import com.xtranslator.XTranslatorMod;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
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
            TranslationService service = manager.getTranslationService();
            if (service == null) return null;
            Map<String, String> cache = service.getTranslationCache();
            String res = cache.get(text);
            if (res != null) return res;
            String trimmed = text.trim();
            if (!trimmed.isEmpty() && !trimmed.equals(text)) {
                return cache.get(trimmed);
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public int drawInBatch(String text, float x, float y, int color, boolean dropShadow, Matrix4f matrix, MultiBufferSource bufferSource, DisplayMode displayMode, int backgroundColor, int packedLightCoords) {
        recordRendered(text);
        String translated = getCachedTranslation(text);
        return super.drawInBatch(translated != null ? translated : text, x, y, color, dropShadow, matrix, bufferSource, displayMode, backgroundColor, packedLightCoords);
    }

    @Override
    public int drawInBatch(Component text, float x, float y, int color, boolean dropShadow, Matrix4f matrix, MultiBufferSource bufferSource, DisplayMode displayMode, int backgroundColor, int packedLightCoords) {
        if (text != null) {
            String raw = text.getString();
            recordRendered(raw);
            String translated = getCachedTranslation(raw);
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
            if (translated != null) {
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
        String translated = getCachedTranslation(text);
        return super.width(translated != null ? translated : text);
    }

    @Override
    public int width(FormattedText text) {
        if (text != null) {
            String raw = text.getString();
            String translated = getCachedTranslation(raw);
            if (translated != null) {
                Style style = text instanceof Component c ? c.getStyle() : Style.EMPTY;
                return super.width(FormattedText.of(translated, style));
            }
        }
        return super.width(text);
    }
}
