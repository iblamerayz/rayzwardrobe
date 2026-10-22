package org.rayz.rayzWardrobe.hooks;

import org.bukkit.inventory.ItemStack;

public class OraxenProvider implements ItemProvider {
    @Override
    public String getPluginName() { return "Oraxen"; }

    @Override
    public boolean isCustomItem(ItemStack item) {
        try {
            Class<?> oraxenItems = Class.forName("io.th0rgal.oraxen.api.OraxenItems");
            return (Boolean) oraxenItems.getMethod("exists", ItemStack.class).invoke(null, item);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            return false;
        }
    }

    @Override
    public String getCustomId(ItemStack item) {
        try {
            Class<?> oraxenItems = Class.forName("io.th0rgal.oraxen.api.OraxenItems");
            return (String) oraxenItems.getMethod("getIdByItem", ItemStack.class).invoke(null, item);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            return null;
        }
    }

    @Override
    public ItemStack getItem(String id) {
        try {
            Class<?> oraxenItems = Class.forName("io.th0rgal.oraxen.api.OraxenItems");
            Object builder = oraxenItems.getMethod("getItemById", String.class).invoke(null, id);
            return builder != null ? (ItemStack) builder.getClass().getMethod("build").invoke(builder) : null;
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            return null;
        }
    }
}
