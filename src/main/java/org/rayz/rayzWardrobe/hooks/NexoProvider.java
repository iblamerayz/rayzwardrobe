package org.rayz.rayzWardrobe.hooks;

import org.bukkit.inventory.ItemStack;

public class NexoProvider implements ItemProvider {
    @Override
    public String getPluginName() { return "Nexo"; }

    @Override
    public boolean isCustomItem(ItemStack item) {
        return getCustomId(item) != null;
    }

    @Override
    public String getCustomId(ItemStack item) {
        try {
            Class<?> nexoItems = Class.forName("com.nexomc.nexo.api.NexoItems");
            return (String) nexoItems.getMethod("idFromItem", ItemStack.class).invoke(null, item);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            return null;
        }
    }

    @Override
    public ItemStack getItem(String id) {
        try {
            Class<?> nexoItems = Class.forName("com.nexomc.nexo.api.NexoItems");
            Object builder = nexoItems.getMethod("itemFromId", String.class).invoke(null, id);
            return builder != null ? (ItemStack) builder.getClass().getMethod("build").invoke(builder) : null;
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            return null;
        }
    }
}
