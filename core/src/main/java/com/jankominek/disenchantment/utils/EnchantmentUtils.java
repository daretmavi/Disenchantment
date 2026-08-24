package com.jankominek.disenchantment.utils;

import com.jankominek.disenchantment.plugins.IPluginEnchantment;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.jankominek.disenchantment.Disenchantment.nms;

/**
 * Utility class for adding, removing, and querying enchantments on items and enchanted books.
 * Handles both regular item enchantments and stored enchantments on books.
 */
public class EnchantmentUtils {

    /**
     * Adds an enchantment directly to an item, bypassing level restrictions.
     *
     * @param item        the item to enchant
     * @param enchantment the enchantment to add
     * @param level       the enchantment level
     */
    public static void addEnchantment(ItemStack item, Enchantment enchantment, Integer level) {
        item.addUnsafeEnchantment(enchantment, level);
    }

    /**
     * Adds a stored enchantment to an enchanted book.
     * For normal items this method falls back to a regular enchantment.
     *
     * @param item        the item to enchant
     * @param enchantment the enchantment to store
     * @param level       the enchantment level
     */
    public static void addStoredEnchantment(ItemStack item, Enchantment enchantment, Integer level) {
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return;
        }

        if (meta instanceof EnchantmentStorageMeta storage) {
            storage.addStoredEnchant(enchantment, level, true);
        } else {
            meta.addEnchant(enchantment, level, true);
        }

        item.setItemMeta(meta);
    }

    /**
     * Creates a clone of the item with the specified enchantments removed.
     *
     * <p>This method removes normal enchantments from regular items and
     * stored enchantments from enchanted books.</p>
     *
     * @param firstItem    the item to clone and modify
     * @param enchantments the enchantments to remove
     * @return a new item stack with the enchantments removed
     */
    public static ItemStack removeEnchantments(
            ItemStack firstItem,
            Map<Enchantment, Integer> enchantments
    ) {
        ItemStack item = firstItem.clone();

        if (enchantments == null || enchantments.isEmpty()) {
            return item;
        }

        for (Enchantment enchantment : enchantments.keySet()) {
            removeEnchantment(item, enchantment);
        }

        return item;
    }

    /**
     * Removes a normal enchantment directly from an item.
     *
     * <p>For enchanted books, this method removes the stored enchantment instead.</p>
     *
     * @param item        the item to modify
     * @param enchantment the enchantment to remove
     */
    public static void removeEnchantment(ItemStack item, Enchantment enchantment) {
        if (item == null || enchantment == null) {
            return;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return;
        }

        if (meta instanceof EnchantmentStorageMeta storage) {
            storage.removeStoredEnchant(enchantment);
        } else {
            meta.removeEnchant(enchantment);
        }

        item.setItemMeta(meta);
    }

    /**
     * Removes a stored enchantment from an enchanted book.
     *
     * <p>For normal items this falls back to removing the regular enchantment.</p>
     *
     * @param item        the item to modify
     * @param enchantment the enchantment to remove
     */
    public static void removeStoredEnchantment(ItemStack item, Enchantment enchantment) {
        removeEnchantment(item, enchantment);
    }

    /**
     * Checks whether items of the given material can be enchanted.
     *
     * @param material the material to check
     * @return true if the material can be enchanted
     */
    public static boolean canItemBeEnchanted(Material material) {
        return EnchantmentUtils.canItemBeEnchanted(new ItemStack(material));
    }

    /**
     * Checks whether the given item stack can be enchanted, via NMS.
     *
     * @param item the item to check
     * @return true if the item can be enchanted
     */
    public static boolean canItemBeEnchanted(ItemStack item) {
        return nms.canItemBeEnchanted(item);
    }

    /**
     * Retrieves all enchantments from an item, supporting both regular and stored enchantments.
     *
     * @param item the item to inspect
     * @return a mutable list of enchantments
     */
    public static List<IPluginEnchantment> getItemEnchantments(ItemStack item) {
        List<IPluginEnchantment> enchantments;

        if (item.hasItemMeta() && item.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            enchantments = meta.getStoredEnchants()
                    .entrySet()
                    .stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() > 0)
                    .map(entry -> remapEnchantment(entry.getKey(), entry.getValue()))
                    .collect(Collectors.toList());
        } else {
            enchantments = item.getEnchantments()
                    .entrySet()
                    .stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() > 0)
                    .map(entry -> remapEnchantment(entry.getKey(), entry.getValue()))
                    .collect(Collectors.toList());
        }

        return enchantments;
    }

    /**
     * Returns all registered enchantments from the server via NMS.
     *
     * @return a list of all registered enchantments
     */
    public static List<Enchantment> getRegisteredEnchantments() {
        return nms.getRegisteredEnchantments();
    }

    /**
     * Wraps a Bukkit enchantment into an IPluginEnchantment implementation.
     *
     * @param enchantment the enchantment
     * @param level       the enchantment level
     * @return an IPluginEnchantment adapter
     */
    public static IPluginEnchantment remapEnchantment(
            Enchantment enchantment,
            int level
    ) {
        return new IPluginEnchantment() {

            @Override
            public String getKey() {
                return enchantment.getKey().getKey().toLowerCase();
            }

            @Override
            public int getLevel() {
                return level;
            }

            @Override
            public ItemStack addToBook(ItemStack book) {
                ItemStack item = book.clone();
                EnchantmentUtils.addStoredEnchantment(
                        item,
                        enchantment,
                        this.getLevel()
                );
                return item;
            }

            @Override
            public ItemStack removeFromBook(ItemStack book) {
                ItemStack item = book.clone();
                EnchantmentUtils.removeStoredEnchantment(
                        item,
                        enchantment
                );
                return item;
            }

            @Override
            public ItemStack addToItem(ItemStack item) {
                ItemStack result = item.clone();
                EnchantmentUtils.addEnchantment(
                        result,
                        enchantment,
                        this.getLevel()
                );
                return result;
            }

            @Override
            public ItemStack removeFromItem(ItemStack item) {
                ItemStack result = item.clone();
                EnchantmentUtils.removeEnchantment(
                        result,
                        enchantment
                );
                return result;
            }
        };
    }
}