package com.xtranslator.resourcepack;

import com.xtranslator.XTranslatorMod;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles automatic activation of the XTranslator resource pack.
 */
public class ResourcePackActivator {
    private static final String PACK_NAME = "file/XTranslator";

    /**
     * Activates the XTranslator resource pack if it exists and is not already active.
     */
    public static void activateResourcePack() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            XTranslatorMod.LOGGER.warn("Minecraft instance not available, cannot activate resource pack");
            return;
        }

        PackRepository packRepository = minecraft.getResourcePackRepository();

        // Reload to detect new packs
        packRepository.reload();

        // Find the XTranslator pack
        Pack xtranslatorPack = packRepository.getPack(PACK_NAME);

        if (xtranslatorPack == null) {
            XTranslatorMod.LOGGER.warn("XTranslator resource pack not found in pack repository");
            return;
        }

        // Get currently selected packs
        List<String> selectedPacks = new ArrayList<>(packRepository.getSelectedIds());

        // Check if already activated
        if (selectedPacks.contains(PACK_NAME)) {
            XTranslatorMod.LOGGER.info("XTranslator resource pack is already active");
            return;
        }

        // Add XTranslator pack to selected packs
        // In Minecraft PackRepository, packs at the end of the list have the highest priority and override earlier packs
        selectedPacks.add(PACK_NAME);

        // Apply the new pack selection
        packRepository.setSelected(selectedPacks);

        XTranslatorMod.LOGGER.info("Activated XTranslator resource pack");

        // Reload resources to apply changes
        minecraft.reloadResourcePacks();
    }

    /**
     * Deactivates the XTranslator resource pack.
     */
    public static void deactivateResourcePack() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }

        PackRepository packRepository = minecraft.getResourcePackRepository();
        List<String> selectedPacks = new ArrayList<>(packRepository.getSelectedIds());

        if (selectedPacks.remove(PACK_NAME)) {
            packRepository.setSelected(selectedPacks);
            minecraft.reloadResourcePacks();
            XTranslatorMod.LOGGER.info("Deactivated XTranslator resource pack");
        }
    }

    /**
     * Checks if the XTranslator resource pack is currently active.
     */
    public static boolean isResourcePackActive() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }

        PackRepository packRepository = minecraft.getResourcePackRepository();
        return packRepository.getSelectedIds().contains(PACK_NAME);
    }

    /**
     * Enables the XTranslator resource pack (alias for activateResourcePack).
     */
    public static void enableResourcePack() {
        activateResourcePack();
    }

    /**
     * Disables the XTranslator resource pack (alias for deactivateResourcePack).
     */
    public static void disableResourcePack() {
        deactivateResourcePack();
    }

    /**
     * Checks if the XTranslator resource pack is enabled (alias for isResourcePackActive).
     */
    public static boolean isResourcePackEnabled() {
        return isResourcePackActive();
    }
}
