package com.xtranslator.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.xtranslator.XTranslationManager;
import com.xtranslator.chat.TranslationProgress;
import com.xtranslator.config.ModConfig;
import com.xtranslator.translation.ScreenPriorityTranslator;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * Clean & Optimized Command Suite for XTranslator:
 * - /xtrans mod <tên_mod> : Dịch riêng một mod chỉ định (hỗ trợ Tab gợi ý)
 * - /xtrans screen        : Dịch toàn bộ menu / màn hình đang mở (hoặc phím tắt V)
 * - /xtrans full          : Dịch toàn bộ các mod trong máy
 * - /xtrans list          : Xem danh sách mod chưa dịch
 * - /xtrans status        : Xem tiến trình dịch
 * - /xtrans edit <key> <> : Sửa nhanh 1 từ/kỹ năng
 * - /xtrans cancel        : Hủy tiến trình dịch
 */
public class TranslationCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String root : new String[]{"xtrans", "xtranslator"}) {
            dispatcher.register(
                Commands.literal(root)
                    .then(Commands.literal("mod")
                        .then(Commands.argument("modid", StringArgumentType.string())
                            .suggests((context, builder) -> {
                                String typed = builder.getRemaining().toLowerCase();
                                for (IModInfo mod : ModList.get().getMods()) {
                                    String id = mod.getModId();
                                    if (!id.equals("minecraft") && !id.equals("neoforge") && id.toLowerCase().startsWith(typed)) {
                                        builder.suggest(id);
                                    }
                                }
                                return builder.buildFuture();
                            })
                            .executes(TranslationCommands::translateSpecificMod)))
                    .then(Commands.literal("match")
                        .then(Commands.argument("keyword", StringArgumentType.string())
                            .suggests((context, builder) -> {
                                String typed = builder.getRemaining().toLowerCase();
                                for (IModInfo mod : ModList.get().getMods()) {
                                    String id = mod.getModId();
                                    if (!id.equals("minecraft") && !id.equals("neoforge") && id.toLowerCase().contains(typed)) {
                                        builder.suggest(id);
                                    }
                                }
                                return builder.buildFuture();
                            })
                            .executes(TranslationCommands::translateByKeyword)))
                    .then(Commands.literal("keyword")
                        .then(Commands.argument("keyword", StringArgumentType.string())
                            .suggests((context, builder) -> {
                                String typed = builder.getRemaining().toLowerCase();
                                for (IModInfo mod : ModList.get().getMods()) {
                                    String id = mod.getModId();
                                    if (!id.equals("minecraft") && !id.equals("neoforge") && id.toLowerCase().contains(typed)) {
                                        builder.suggest(id);
                                    }
                                }
                                return builder.buildFuture();
                            })
                            .executes(TranslationCommands::translateByKeyword)))
                    .then(Commands.literal("screen")
                        .executes(TranslationCommands::translateScreen))
                    .then(Commands.literal("full")
                        .executes(TranslationCommands::startFullScan))
                    .then(Commands.literal("list")
                        .then(Commands.argument("filter", StringArgumentType.string())
                            .executes(TranslationCommands::listModsFiltered))
                        .executes(TranslationCommands::listMods))
                    .then(Commands.literal("status")
                        .executes(TranslationCommands::status))
                    .then(Commands.literal("edit")
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                            .executes(TranslationCommands::editKey)))
                    .then(Commands.literal("cancel")
                        .executes(TranslationCommands::cancel))
                    .then(Commands.literal("clear")
                        .then(Commands.argument("modid", StringArgumentType.string())
                            .suggests((context, builder) -> {
                                String typed = builder.getRemaining().toLowerCase();
                                builder.suggest("all");
                                for (IModInfo mod : ModList.get().getMods()) {
                                    String id = mod.getModId();
                                    if (!id.equals("minecraft") && !id.equals("neoforge") && id.toLowerCase().startsWith(typed)) {
                                        builder.suggest(id);
                                    }
                                }
                                return builder.buildFuture();
                            })
                            .executes(TranslationCommands::clearMod))
                        .executes(TranslationCommands::clearAll))
                    .then(Commands.literal("auto")
                        .then(Commands.literal("on").executes(ctx -> setAuto(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setAuto(ctx, false)))
                        .executes(TranslationCommands::toggleAuto))
                    .then(Commands.literal("help")
                        .executes(TranslationCommands::help))
                    .executes(TranslationCommands::help)
            );
        }
    }

    private static int translateSpecificMod(CommandContext<CommandSourceStack> context) {
        String modid = StringArgumentType.getString(context, "modid");
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            XTranslationManager manager = XTranslationManager.getInstance();
            manager.translateSpecificMod(modid);
        });
        return 1;
    }

    private static int translateScreen(CommandContext<CommandSourceStack> context) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.screen != null && !(minecraft.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) {
                ScreenPriorityTranslator.scanAndTranslateScreen(minecraft.screen, true);
            } else if (ScreenPriorityTranslator.lastOpenedModId != null) {
                XTranslationManager manager = XTranslationManager.getInstance();
                manager.translateSpecificMod(ScreenPriorityTranslator.lastOpenedModId);
            } else if (ScreenPriorityTranslator.lastOpenedScreen != null) {
                ScreenPriorityTranslator.scanAndTranslateScreen(ScreenPriorityTranslator.lastOpenedScreen, true);
            } else {
                TranslationProgress.sendChatMessage(Component.translatable("xtranslator.chat.prefix")
                        .append(Component.translatable("xtranslator.chat.no_screen")));
            }
        });
        return 1;
    }

    private static int startFullScan(CommandContext<CommandSourceStack> context) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            XTranslationManager manager = XTranslationManager.getInstance();
            manager.startTranslation(true);
        });
        return 1;
    }

    private static int translateByKeyword(CommandContext<CommandSourceStack> context) {
        String keyword = StringArgumentType.getString(context, "keyword");
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            XTranslationManager manager = XTranslationManager.getInstance();
            manager.translateModsByKeyword(keyword);
        });
        return 1;
    }

    private static int listMods(CommandContext<CommandSourceStack> context) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            XTranslationManager manager = XTranslationManager.getInstance();
            manager.listModsWithMissingTranslations();
        });
        return 1;
    }

    private static int listModsFiltered(CommandContext<CommandSourceStack> context) {
        String filter = StringArgumentType.getString(context, "filter");
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            XTranslationManager manager = XTranslationManager.getInstance();
            manager.listModsWithMissingTranslations(filter);
        });
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        String targetLang = TranslationProgress.getTargetLanguage();
        String langName = TranslationProgress.getLanguageDisplayName(targetLang);
        XTranslationManager manager = XTranslationManager.getInstance();
        int cacheCount = manager.getTranslationService() != null ? manager.getTranslationService().getCacheSize() : 0;
        int indexedCount = manager.getUntranslatedKeysCache().size();
        String lastMod = ScreenPriorityTranslator.lastOpenedModId != null ? ScreenPriorityTranslator.lastOpenedModId : "-";

        Component header = Component.translatable("xtranslator.cmd.status.header");
        Component target = Component.translatable("xtranslator.cmd.status.target", langName, targetLang);
        Component cached = Component.translatable("xtranslator.cmd.status.cached", cacheCount);
        Component indexed = Component.translatable("xtranslator.cmd.status.indexed", indexedCount);
        Component lastMenu = Component.translatable("xtranslator.cmd.status.last_menu", lastMod);

        if (TranslationProgress.isTranslating()) {
            int percentage = (int) (TranslationProgress.getProgress() * 100);
            int current = TranslationProgress.getCompletedItems();
            int total = TranslationProgress.getTotalItems();
            String bar = TranslationProgress.getProgressBar(current, total, 10);
            context.getSource().sendSuccess(() -> Component.literal("")
                    .append(header)
                    .append("\n").append(Component.translatable("xtranslator.cmd.status.busy"))
                    .append("\n").append(Component.literal("§fProgress: §8[" + bar + "§8] §a" + current + "§7/§a" + total + " §7(" + percentage + "%)"))
                    .append("\n").append(target)
                    .append("\n").append(lastMenu)
                    .append("\n").append(cached), false);
        } else {
            context.getSource().sendSuccess(() -> Component.literal("")
                    .append(header)
                    .append("\n").append(Component.translatable("xtranslator.cmd.status.idle"))
                    .append("\n").append(target)
                    .append("\n").append(lastMenu)
                    .append("\n").append(cached)
                    .append("\n").append(indexed), false);
        }
        return 1;
    }

    private static int editKey(CommandContext<CommandSourceStack> context) {
        String args = StringArgumentType.getString(context, "args").trim();
        int firstSpace = args.indexOf(' ');
        if (firstSpace == -1) {
            context.getSource().sendFailure(Component.literal("§c")
                    .append(Component.translatable("xtranslator.cmd.edit.syntax")));
            return 0;
        }

        String key = args.substring(0, firstSpace).trim();
        String newTranslation = args.substring(firstSpace + 1).trim();

        XTranslationManager manager = XTranslationManager.getInstance();
        manager.editSingleTranslation(key, newTranslation);

        context.getSource().sendSuccess(() -> Component.literal("§a[XTranslator] §f")
                .append(Component.translatable("xtranslator.cmd.edit.success", key + " ➔ " + newTranslation)), false);
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> context) {
        if (TranslationProgress.isTranslating()) {
            TranslationProgress.cancel();
            context.getSource().sendSuccess(() -> Component.literal("§c[XTranslator] ")
                    .append(Component.translatable("xtranslator.cmd.cancel.success")), false);
        } else {
            context.getSource().sendFailure(Component.literal("§e[XTranslator] ")
                    .append(Component.translatable("xtranslator.cmd.cancel.none")));
        }
        return 1;
    }

    private static int clearAll(CommandContext<CommandSourceStack> context) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            XTranslationManager manager = XTranslationManager.getInstance();
            manager.clearAllTranslations();
            TranslationProgress.sendChatMessage(Component.translatable("xtranslator.chat.prefix")
                    .append(Component.translatable("xtranslator.cmd.clear.success")));
        });
        return 1;
    }

    private static int clearMod(CommandContext<CommandSourceStack> context) {
        String modid = StringArgumentType.getString(context, "modid");
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            XTranslationManager manager = XTranslationManager.getInstance();
            manager.clearTranslations(modid);
            TranslationProgress.sendChatMessage(Component.translatable("xtranslator.chat.prefix")
                    .append(Component.translatable("xtranslator.cmd.clear.success")));
        });
        return 1;
    }

    private static int toggleAuto(CommandContext<CommandSourceStack> context) {
        boolean current = ModConfig.AUTO_TRANSLATE_BACKGROUND.get();
        return setAuto(context, !current);
    }

    private static int setAuto(CommandContext<CommandSourceStack> context, boolean enable) {
        ModConfig.AUTO_TRANSLATE_BACKGROUND.set(enable);
        Minecraft.getInstance().execute(() -> {
            if (enable) {
                TranslationProgress.sendChatMessage(Component.literal("§a[XTranslator] §fĐã §aBẬT §ftự động dịch ngầm (Background Auto-Translate)."));
            } else {
                TranslationProgress.sendChatMessage(Component.literal("§e[XTranslator] §fĐã §cTẮT §ftự động dịch ngầm. Chỉ dịch khi dùng lệnh (/xtrans mod) hoặc phím V."));
            }
        });
        return 1;
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal("")
                .append(Component.translatable("xtranslator.cmd.help.header"))
                .append("\n").append(Component.translatable("xtranslator.cmd.help.screen"))
                .append("\n").append(Component.translatable("xtranslator.cmd.help.mod"))
                .append("\n").append(Component.translatable("xtranslator.cmd.help.full"))
                .append("\n").append("§e/xtrans auto <on|off> §7- Bật/tắt tự động dịch ngầm khi rê chuột")
                .append("\n").append(Component.translatable("xtranslator.cmd.help.status"))
                .append("\n").append(Component.translatable("xtranslator.cmd.help.clear"))
                .append("\n").append(Component.translatable("xtranslator.cmd.help.cancel"))
                .append("\n").append(Component.translatable("xtranslator.cmd.help.edit")), false);
        return 1;
    }
}
