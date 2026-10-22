package org.rayz.rayzWardrobe.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.rayz.rayzWardrobe.hooks.HookManager;
import org.rayz.rayzWardrobe.hooks.ItemProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Handles ItemStack serialization with Paper's byte serializer and a
 * Bukkit object-stream fallback for legacy saved data.
 */
public class ItemSerializer {

    public static String itemStackToBase64(ItemStack item) {
        if (isEmpty(item)) {
            return null;
        }

        try {
            String base64Fallback = serializeItem(item);
            ItemProvider provider = HookManager.getProviderForItem(item);
            if (provider != null) {
                String customId = provider.getCustomId(item);
                if (customId != null && !customId.isBlank()) {
                    return "CUSTOM|" + provider.getPluginName() + "|" + customId + "|" + base64Fallback;
                }
            }
            return "VANILLA|" + base64Fallback;
        } catch (Exception e) {
            Bukkit.getLogger().severe("[RayzWardrobe] Failed to serialize ItemStack: " + e.getMessage());
            return null;
        }
    }

    public static ItemStack itemStackFromBase64(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }

        try {
            if (data.startsWith("CUSTOM|")) {
                String[] parts = data.split("\\|", 4);
                if (parts.length == 4) {
                    ItemStack serializedItem = deserializeItem(parts[3]);
                    if (!isEmpty(serializedItem)) {
                        return serializedItem;
                    }

                    ItemProvider provider = HookManager.getProvider(parts[1]);
                    if (provider != null) {
                        ItemStack customItem = provider.getItem(parts[2]);
                        if (!isEmpty(customItem)) {
                            return customItem;
                        }
                    }
                    return deserializeItem(parts[3]);
                }
            }

            if (data.startsWith("VANILLA|")) {
                return deserializeItem(data.substring(8));
            }

            return deserializeItem(data);
        } catch (Exception e) {
            Bukkit.getLogger().severe("[RayzWardrobe] Failed to deserialize ItemStack: " + e.getMessage());
            return null;
        }
    }

    private static String serializeItem(ItemStack item) throws Exception {
        try {
            byte[] bytes = item.serializeAsBytes();
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Throwable ignored) {
            try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
                 BukkitObjectOutputStream objectStream = new BukkitObjectOutputStream(byteStream)) {
                objectStream.writeObject(item);
                return Base64.getEncoder().encodeToString(byteStream.toByteArray());
            }
        }
    }

    private static ItemStack deserializeItem(String base64) throws Exception {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        byte[] bytes = Base64.getDecoder().decode(base64);
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (Throwable ignored) {
            try (ByteArrayInputStream byteStream = new ByteArrayInputStream(bytes);
                 BukkitObjectInputStream objectStream = new BukkitObjectInputStream(byteStream)) {
                Object object = objectStream.readObject();
                return object instanceof ItemStack ? (ItemStack) object : null;
            }
        }
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }
}
