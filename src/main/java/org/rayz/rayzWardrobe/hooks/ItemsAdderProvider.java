package org.rayz.rayzWardrobe.hooks;

import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

public class ItemsAdderProvider implements ItemProvider {
    @Override
    public String getPluginName() { return "ItemsAdder"; }

    @Override
    public boolean isCustomItem(ItemStack item) {
        return customStackByItem(item) != null;
    }

    @Override
    public String getCustomId(ItemStack item) {
        Object stack = customStackByItem(item);
        if (stack == null) return null;
        try {
            return (String) stack.getClass().getMethod("getNamespacedID").invoke(stack);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            return null;
        }
    }

    @Override
    public ItemStack getItem(String id) {
        try {
            Class<?> customStack = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Object stack = customStack.getMethod("getInstance", String.class).invoke(null, id);
            return stack != null ? (ItemStack) stack.getClass().getMethod("getItemStack").invoke(stack) : null;
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            return null;
        }
    }

    private Object customStackByItem(ItemStack item) {
        if (item == null) return null;
        try {
            Class<?> customStack = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Method byItemStack = customStack.getMethod("byItemStack", ItemStack.class);
            return byItemStack.invoke(null, item);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            return null;
        }
    }
}
