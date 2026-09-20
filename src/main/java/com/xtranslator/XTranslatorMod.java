package com.xtranslator;

import com.xtranslator.commands.TranslationCommands;
import com.xtranslator.config.ModConfig;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import java.nio.file.Path;

@Mod(XTranslatorMod.MODID)
public class XTranslatorMod {

    public static final String MODID = "xtranslator";
    public static final Logger LOGGER = LogUtils.getLogger();

    public XTranslatorMod(IEventBus modEventBus, ModContainer modContainer) {
        // Register configuration
        modContainer.registerConfig(Type.CLIENT, ModConfig.SPEC);

        // Register client-side event handlers
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onAddPackFinders);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("XTranslator mod initializing...");

        // Register command handler
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        LOGGER.info("Registered command handler");
    }

    private void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        TranslationCommands.register(event.getDispatcher());
        LOGGER.info("Registered XTranslator commands");
    }

    private void onAddPackFinders(AddPackFindersEvent event) {
        LOGGER.debug("Pack finders event fired");
    }

    /**
     * Client-side event handlers.
     * Only registered on client distribution.
     */
    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {

        /**
         * Registers a resource reload listener that triggers translation
         * whenever resources are reloaded (including language changes).
         */
        @SubscribeEvent
        public static void onRegisterClientReloadListeners(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event) {
            LOGGER.info("Registering XTranslator reload listener");

            event.registerReloadListener((synchronizer, resourceManager, preparationsProfiler, reloadProfiler, backgroundExecutor, gameExecutor) -> {
                return synchronizer.wait(null).thenRunAsync(() -> {
                    LOGGER.info("Resources reloaded, triggering translation...");

                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft == null) {
                        LOGGER.error("Minecraft instance is null");
                        return;
                    }

                    Path gameDirectory = minecraft.gameDirectory.toPath();

                    // Initialize and start translation manager
                    // This will run on every resource reload (including language changes)
                    XTranslationManager manager = XTranslationManager.getInstance(gameDirectory);
                    manager.startTranslation();

                    LOGGER.info("Translation process initiated after resource reload");
                }, gameExecutor);
            });
        }
    }
}
