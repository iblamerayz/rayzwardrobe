package org.rayz.rayzWardrobe.hooks;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Method;

public class EcoItemsProvider implements ItemProvider {
    @Override
    public String getPluginName() { return "EcoItems"; }

    @Override
    public boolean isCustomItem(ItemStack item) {
        return getCustomId(item) != null;
    }

    @Override
    public String getCustomId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        try {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            for (NamespacedKey key : pdc.getKeys()) {
                String ns = key.getNamespace().toLowerCase();
                String k = key.getKey().toLowerCase();
                if ((ns.equals("ecoitems") || ns.equals("eco")) && (k.equals("id") || k.equals("item"))) {
                    String val = pdc.get(key, PersistentDataType.STRING);
                    if (val != null && !val.isBlank()) return val;
                }
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> itemsClass = Class.forName("com.william278.ecoitems.items.EcoItem");
            Method getID = itemsClass.getMethod("getID", ItemStack.class);
            return (String) getID.invoke(null, item);
        } catch (Throwable ignored) {}

        try {
            Class<?> apiClass = Class.forName("com.auxilor.eco.api.items.Items");
            Method getCustomItem = apiClass.getMethod("getCustomItem", ItemStack.class);
            Object customItem = getCustomItem.invoke(null, item);
            if (customItem != null) {
                Method getKey = customItem.getClass().getMethod("getKey");
                Object keyObj = getKey.invoke(customItem);
                return keyObj != null ? keyObj.toString() : null;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    @Override
    public ItemStack getItem(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            Class<?> apiClass = Class.forName("com.auxilor.eco.api.items.Items");
            Method lookup = apiClass.getMethod("lookup", String.class);
            Object customItem = lookup.invoke(null, id);
            if (customItem != null) {
                Method getItem = customItem.getClass().getMethod("getItem");
                return (ItemStack) getItem.invoke(customItem);
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> itemsClass = Class.forName("com.william278.ecoitems.EcoItems");
            Method getItem = itemsClass.getMethod("getItem", String.class);
            return (ItemStack) getItem.invoke(null, id);
        } catch (Throwable ignored) {}

        return null;
    }
}
