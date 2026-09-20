package com.xtranslator.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ModConfig {
    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue AUTO_TRANSLATE_BACKGROUND;
    public static final ModConfigSpec.BooleanValue AUTO_ACTIVATE_RESOURCEPACK;
    public static final ModConfigSpec.IntValue TRANSLATION_DELAY_MS;
    public static final ModConfigSpec.ConfigValue<String> SOURCE_LANGUAGE;
    public static final ModConfigSpec.ConfigValue<String> TARGET_LANGUAGE;
    public static final ModConfigSpec.ConfigValue<String> TRANSLATION_MODE;

    static {
        BUILDER.push("XTranslator Configuration");

        ENABLED = BUILDER
                .comment("Enable XTranslator mod")
                .define("enabled", true);

        AUTO_TRANSLATE_BACKGROUND = BUILDER
                .comment("Automatically translate text in the background while playing or browsing items.",
                        "Default: false (Recommended: prevents HTTP 429 rate limit bans).",
                        "When false, translations only happen when you use commands (/xtrans mod, /xtrans screen) or press 'V'.")
                .define("autoTranslateBackground", false);

        AUTO_ACTIVATE_RESOURCEPACK = BUILDER
                .comment("Automatically activate the generated resource pack")
                .define("autoActivateResourcePack", true);

        TRANSLATION_DELAY_MS = BUILDER
                .comment("Delay between translation batch requests (in milliseconds)",
                        "Default: 250ms. Prevents rate limiting by translation APIs.")
                .defineInRange("translationDelayMs", 250, 0, 5000);

        SOURCE_LANGUAGE = BUILDER
                .comment("Source language of the mods to translate from (e.g., 'auto', 'en_us', 'zh_cn', 'ja_jp').",
                        "If set to 'auto', the mod automatically detects available language files in each mod.",
                        "Default: 'auto'.")
                .define("sourceLanguage", "auto");

        TARGET_LANGUAGE = BUILDER
                .comment("Target language to translate into (e.g., 'auto', 'vi_vn', 'ja_jp', 'ru_ru', 'zh_cn').",
                        "If set to 'auto', it automatically syncs with your in-game Minecraft language setting.",
                        "Default: 'auto'.")
                .define("targetLanguage", "auto");

        TRANSLATION_MODE = BUILDER
                .comment("Translation mode: 'ON_DEMAND' or 'FULL'.",
                        "ON_DEMAND: Translates items and tooltips on-screen in real-time as you hover over them (Fastest, zero lag, recommended for big modpacks!).",
                        "FULL: Scans and translates all 100k+ strings of all mods upfront.",
                        "Default: 'ON_DEMAND'.")
                .define("mode", "ON_DEMAND");

        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}
