package org.rayz.rayzWardrobe.hooks;

import org.bukkit.inventory.ItemStack;

public interface ItemProvider {
    String getPluginName();
    boolean isCustomItem(ItemStack item);
    String getCustomId(ItemStack item);
    ItemStack getItem(String id);
}