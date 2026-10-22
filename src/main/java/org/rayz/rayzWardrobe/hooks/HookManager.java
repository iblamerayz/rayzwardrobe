package org.rayz.rayzWardrobe.hooks;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class HookManager {
    private static final Map<String, ItemProvider> providers = new LinkedHashMap<>();

    public static void init() {
        providers.clear();
        registerHook("ItemsAdder", new ItemsAdderProvider());
        registerHook("Oraxen", new OraxenProvider());
        registerHook("Nexo", new NexoProvider());
        registerHook("EcoItems", new EcoItemsProvider());
        registerHook("EcoArmor", new EcoArmorProvider());
    }

    private static void registerHook(String pluginName, ItemProvider provider) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        if (plugin != null && plugin.isEnabled()) {
            providers.put(pluginName.toLowerCase(Locale.ROOT), provider);
            Bukkit.getLogger().info("[RayzWardrobe] Successfully hooked into " + pluginName + " for custom item support!");
        }
    }

    public static ItemProvider getProvider(String pluginName) {
        return pluginName == null ? null : providers.get(pluginName.toLowerCase(Locale.ROOT));
    }

    public static ItemProvider getProviderForItem(ItemStack item) {
        if (item == null || item.getType() == org.bukkit.Material.AIR || item.getAmount() <= 0) return null;
        for (ItemProvider provider : providers.values()) {
            try {
                if (provider.isCustomItem(item)) {
                    return provider;
                }
            } catch (Exception | LinkageError ignored) {
                // Ignore exceptions caused by optional plugins not being fully initialized yet.
            }
        }
        return null;
    }
}
