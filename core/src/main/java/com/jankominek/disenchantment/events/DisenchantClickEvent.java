package com.jankominek.disenchantment.events;

import com.jankominek.disenchantment.config.Config;
import com.jankominek.disenchantment.config.I18n;
import com.jankominek.disenchantment.events.api.PostDisenchantEvent;
import com.jankominek.disenchantment.events.api.PreDisenchantEvent;
import com.jankominek.disenchantment.plugins.IPluginEnchantment;
import com.jankominek.disenchantment.plugins.ISupportedPlugin;
import com.jankominek.disenchantment.plugins.SupportedPluginManager;
import com.jankominek.disenchantment.types.PermissionGroupType;
import com.jankominek.disenchantment.utils.*;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handles the {@link InventoryClickEvent} when a player clicks the result slot
 * of an anvil during a disenchantment operation.
 */
public class DisenchantClickEvent {

    public static void onEvent(Event event) {
        try {
            handler(event);
        } catch (Exception e) {
            DiagnosticUtils.throwReport(e);
        }
    }

    private static final AnvilEventGuards.EconomyConfig ECONOMY_CONFIG =
            new AnvilEventGuards.EconomyConfig() {

                @Override
                public boolean isEnabled() {
                    return Config.Disenchantment.Economy.isEnabled();
                }

                @Override
                public double getCost() {
                    return Config.Disenchantment.Economy.getCost();
                }

                @Override
                public boolean isChargeMessageEnabled() {
                    return Config.Disenchantment.Economy.isChargeMessageEnabled();
                }
            };

    private static void handler(Event event) {

        if (!(event instanceof InventoryClickEvent e)) return;

        Player p = AnvilEventGuards.getPlayer(e);
        if (p == null) return;

        if (!Config.isPluginEnabled() || !Config.Disenchantment.isEnabled()) return;

        if (!AnvilEventGuards.isAnvilResultSlotClick(e, p)) return;
        if (AnvilEventGuards.isMaintenanceBlocked(p)) return;

        if (AnvilEventGuards.isWorldBlocked(
                p,
                Config.Disenchantment.isWorldRestricted(p.getWorld())
        )) {
            return;
        }

        if (AnvilEventGuards.isOnCooldown(p)) {
            e.setCancelled(true);
            return;
        }

        AnvilInventory anvilInventory = (AnvilInventory) e.getInventory();

        ItemStack result = anvilInventory.getItem(2);

        if (result == null) return;
        if (result.getType() != Material.ENCHANTED_BOOK) return;

        DiagnosticUtils.debug(
                "DISENCHANT",
                () -> "Click: player=" + p.getName()
                        + ", result=" + result.getType()
                        + ", gameMode=" + p.getGameMode()
        );

        ItemStack firstItem = anvilInventory.getItem(0);
        ItemStack secondItem = anvilInventory.getItem(1);

        if (firstItem == null) return;
        if (secondItem == null) return;

        List<ISupportedPlugin> activatedPlugins =
                SupportedPluginManager.getAllActivatedPlugins();

        List<IPluginEnchantment> enchantments =
                AnvilEventGuards.collectEnchantments(
                        firstItem,
                        secondItem,
                        false,
                        EventUtils.Disenchantment::getDisenchantedEnchantments,
                        EventUtils.Disenchantment::getDisenchantedEnchantments,
                        p.getWorld(),
                        p
                );

        if (enchantments.isEmpty()) {
            DiagnosticUtils.debug(
                    "DISENCHANT",
                    "Click: no eligible enchantments → exit"
            );
            return;
        }

        if (AnvilEventGuards.isUnsafeResultClick(e)) {
            DiagnosticUtils.debug(
                    "DISENCHANT",
                    () -> "Click: unsafe click type "
                            + e.getClick()
                            + " → CANCELLED"
            );
            e.setCancelled(true);
            return;
        }

        if (DiagnosticUtils.isDebugEnabled()) {
            String names = enchantments.stream()
                    .map(ench -> ench.getKey() + ":" + ench.getLevel())
                    .collect(Collectors.joining(", "));

            DiagnosticUtils.debug(
                    "DISENCHANT",
                    "Click: enchantments=[" + names + "]"
            );
        }

        int repairCost =
                AnvilEventGuards.peekBypassCost(
                        p,
                        AnvilCostUtils.getRepairCost(
                                anvilInventory,
                                e.getView()
                        )
                );

        if (!AnvilEventGuards.hasEnoughXp(p, repairCost)) {
            DiagnosticUtils.debug(
                    "DISENCHANT",
                    "Click: insufficient XP → CANCELLED"
            );
            e.setCancelled(true);
            return;
        }

        if (!PermissionGroupType.DISENCHANT_EVENT.hasPermission(p)) {
            DiagnosticUtils.debug(
                    "DISENCHANT",
                    "Click: permission denied → exit"
            );
            return;
        }

        PreDisenchantEvent preEvent =
                new PreDisenchantEvent(
                        p,
                        firstItem.clone(),
                        new ArrayList<>(enchantments)
                );

        org.bukkit.Bukkit
                .getPluginManager()
                .callEvent(preEvent);

        if (preEvent.isCancelled()) {
            e.setCancelled(true);
            return;
        }

        double economyCost =
                AnvilCostUtils.economyCostForEnchantments(
                        preEvent.getEnchantments(),
                        Config.Disenchantment.Economy.getCost(),
                        Config.Disenchantment.Anvil.Repair.getEnchantmentEconomyCosts()
                );

        AnvilEventGuards.EconomyResult economyResult =
                AnvilEventGuards.processEconomyCost(
                        p,
                        ECONOMY_CONFIG,
                        economyCost
                );

        if (economyResult ==
                AnvilEventGuards.EconomyResult.NOT_AVAILABLE) {

            p.sendMessage(
                    I18n.getPrefix()
                            + " "
                            + I18n.Messages.economyNotAvailable()
            );

            e.setCancelled(true);
            return;
        }

        if (economyResult ==
                AnvilEventGuards.EconomyResult.INSUFFICIENT_FUNDS) {

            p.sendMessage(
                    I18n.getPrefix()
                            + " "
                            + I18n.Messages.economyInsufficientFunds(
                                    EconomyUtils.format(economyCost)
                            )
            );

            e.setCancelled(true);
            return;
        }

        AnvilEventGuards.clearBypassCost(p);

        int exp = p.getLevel() - repairCost;

        // ------------------------------------------------------------------------------------------------
        // REMOVE ENCHANTMENTS FROM ORIGINAL ITEM
        // ------------------------------------------------------------------------------------------------

        ItemStack finalFirstItem = firstItem.clone();

        /*
         * IMPORTANT:
         *
         * Always remove the vanilla/Bukkit enchantments from the original item.
         *
         * This must NOT depend on whether a custom enchantment plugin is active.
         * Previously this happened only inside:
         *
         *     if (activatedPlugins.isEmpty())
         *
         * which caused vanilla enchantments to remain on the original item whenever
         * a supported custom-enchantment plugin was installed.
         */

        EnchantmentStorageMeta resultItemMeta =
                result.getItemMeta() instanceof EnchantmentStorageMeta meta
                        ? meta
                        : null;

        if (resultItemMeta != null) {

            Map<org.bukkit.enchantments.Enchantment, Integer> storedEnchants =
                    new HashMap<>(resultItemMeta.getStoredEnchants());

            finalFirstItem =
                    EnchantmentUtils.removeEnchantments(
                            finalFirstItem,
                            storedEnchants
                    );
        }

        /*
         * Remove custom/plugin enchantments.
         *
         * These adapters are responsible for removing their own custom
         * enchantment data from the item.
         */
        for (ISupportedPlugin activatedPlugin : activatedPlugins) {

            List<IPluginEnchantment> pluginEnchantments =
                    activatedPlugin.getItemEnchantments(
                            result,
                            p.getWorld()
                    );

            for (IPluginEnchantment enchantment : pluginEnchantments) {

                finalFirstItem =
                        enchantment.removeFromItem(finalFirstItem);
            }
        }

        /*
         * DELETE-state enchantments must also be removed even if they were
         * not transferred to the result book.
         */
        List<IPluginEnchantment> enchantmentsToDelete =
                EventUtils.Disenchantment.findEnchantmentsToDelete(
                        preEvent.getEnchantments()
                );

        for (IPluginEnchantment enchantment : enchantmentsToDelete) {

            finalFirstItem =
                    enchantment.removeFromItem(finalFirstItem);
        }

        // ------------------------------------------------------------------------------------------------
        // RESET REPAIR COST
        // ------------------------------------------------------------------------------------------------

        if (Config.Disenchantment.Anvil.Repair.isResetEnabled()) {
            AnvilCostUtils.setItemRepairCost(
                    finalFirstItem,
                    0
            );
        }

        /*
         * Put the cleaned item back into the first anvil slot.
         */
        anvilInventory.setItem(
                0,
                finalFirstItem
        );

        /*
         * Remove the input book.
         */
        AnvilEventGuards.scheduleSecondItemRemoval(
                p,
                anvilInventory,
                secondItem
        );

        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            p.setLevel(exp);
        }

        // ------------------------------------------------------------------------------------------------
        // PER-ENCHANTMENT CHANCE
        // ------------------------------------------------------------------------------------------------

        if (resultItemMeta != null) {

            Map<org.bukkit.enchantments.Enchantment, Integer> stored =
                    new HashMap<>(
                            resultItemMeta.getStoredEnchants()
                    );

            boolean rollChanged = false;

            for (Map.Entry<
                    org.bukkit.enchantments.Enchantment,
                    Integer
                    > entry : stored.entrySet()) {

                double chance =
                        Config.Disenchantment.getEnchantmentChance(
                                entry.getKey().getKey().getKey()
                        );

                if (chance < 1.0 && Math.random() >= chance) {

                    resultItemMeta.removeStoredEnchant(
                            entry.getKey()
                    );

                    rollChanged = true;
                }
            }

            if (rollChanged) {
                result.setItemMeta(resultItemMeta);
            }
        }

        // ------------------------------------------------------------------------------------------------
        // GIVE BOOK TO PLAYER
        // ------------------------------------------------------------------------------------------------

        p.setItemOnCursor(result);

        boolean creative =
                p.getGameMode() == org.bukkit.GameMode.CREATIVE;

        int xpCost =
                creative
                        ? 0
                        : repairCost;

        double finalEconomyCost =
                (
                        Config.Disenchantment.Economy.isEnabled()
                                && !creative
                )
                        ? economyCost
                        : 0.0;

        AnvilEventGuards.recordCooldownOperation(p);

        org.bukkit.Bukkit
                .getPluginManager()
                .callEvent(
                        new PostDisenchantEvent(
                                p,
                                result.clone(),
                                finalFirstItem.clone(),
                                xpCost,
                                finalEconomyCost
                        )
                );

        if (Config.Disenchantment.Anvil.Sound.isEnabled()) {

            p.playSound(
                    p.getLocation(),
                    Sound.BLOCK_ANVIL_USE,
                    Config.Disenchantment.Anvil.Sound
                            .getVolume()
                            .floatValue(),
                    Config.Disenchantment.Anvil.Sound
                            .getPitch()
                            .floatValue()
            );
        }

        DiagnosticUtils.debug(
                "DISENCHANT",
                "Click: complete ✓"
        );
    }
}