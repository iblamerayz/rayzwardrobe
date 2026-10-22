package org.rayz.rayzWardrobe;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rayz.rayzWardrobe.RayzWardrobe.ArmorSet;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class WardrobePlaceholderExpansion extends PlaceholderExpansion {
    private final RayzWardrobe plugin;

    public WardrobePlaceholderExpansion(RayzWardrobe plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "rayzwardrobe";
    }

    @Override
    public @NotNull String getAuthor() {
        String authors = String.join(", ", plugin.getDescription().getAuthors());
        return authors.isBlank() ? "Rayz" : authors;
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, String params) {
        if (params == null || params.isBlank()) {
            return "";
        }
        String key = params.toLowerCase(Locale.ROOT);
        Player player = offlinePlayer != null ? offlinePlayer.getPlayer() : null;
        UUID uuid = offlinePlayer != null ? offlinePlayer.getUniqueId() : null;

        if (key.equals("version")) return plugin.getDescription().getVersion();
        if (key.equals("storage_type")) return plugin.getStorageType();
        if (key.equals("max_sets")) return String.valueOf(plugin.getConfiguredMaxArmorSets());
        if (key.equals("max_pages")) return String.valueOf(plugin.getConfiguredMaxPages());
        if (key.equals("tools_enabled")) return String.valueOf(plugin.isToolStorageEnabled());
        if (key.equals("max_tools_per_set")) return String.valueOf(plugin.getConfiguredMaxStoredTools());
        if (key.equals("theme_count")) return String.valueOf(plugin.getThemeCount());
        if (key.equals("placeholder_count")) return String.valueOf(getRegisteredPlaceholderCount());

        if (uuid == null) {
            return "";
        }

        List<ArmorSet> sets = plugin.getArmorSetsFor(uuid);
        String selectedThemeKey = plugin.getSelectedThemeKey(uuid);

        switch (key) {
            case "total_sets":
            case "used_slots":
                return String.valueOf(plugin.countUsedArmorSets(sets));
            case "empty_slots":
                return String.valueOf(Math.max(0, plugin.getConfiguredMaxArmorSets() - plugin.countUsedArmorSets(sets)));
            case "current_page":
                return String.valueOf(plugin.getCurrentPage(uuid));
            case "selected_theme":
                return plugin.getThemeName(selectedThemeKey);
            case "selected_theme_key":
                return selectedThemeKey;
            case "renaming":
                return String.valueOf(plugin.isRenaming(uuid));
            case "cooldown":
            case "cooldown_remaining":
                return String.valueOf(plugin.getCooldownRemainingSeconds(uuid));
            case "can_use":
                return String.valueOf(player != null && player.hasPermission("rayzwardrobe.use"));
            case "can_themes":
                return String.valueOf(player != null && player.hasPermission("rayzwardrobe.themes"));
            default:
                return parseSlotPlaceholder(player, sets, key);
        }
    }

    private String parseSlotPlaceholder(Player player, List<ArmorSet> sets, String key) {
        if (key == null || !key.startsWith("slot_")) return null;

        String[] parts = key.split("_");
        if (parts.length != 3) return null;

        int oneBasedSlot;
        try {
            oneBasedSlot = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ignored) {
            return null;
        }

        if (oneBasedSlot < 1) return null;

        int zeroBasedSlot = oneBasedSlot - 1;
        ArmorSet set = (sets != null && zeroBasedSlot < sets.size()) ? sets.get(zeroBasedSlot) : null;

        switch (parts[2]) {
            case "name":
                return set != null ? set.getName() : "";
            case "exists":
                return String.valueOf(set != null);
            case "permission":
            case "access":
                return String.valueOf(player != null && plugin.hasWardrobeSlotAccess(player, oneBasedSlot));
            default:
                return null;
        }
    }

    public List<String> getRegisteredPlaceholders() {
        return Collections.unmodifiableList(List.of(
                "version", "storage_type", "max_sets", "max_pages", "theme_count", "placeholder_count",
                "tools_enabled", "max_tools_per_set",
                "total_sets", "used_slots", "empty_slots", "current_page", "selected_theme", "selected_theme_key",
                "renaming", "cooldown", "cooldown_remaining", "can_use", "can_themes",
                "slot_<number>_name", "slot_<number>_exists", "slot_<number>_permission", "slot_<number>_access"
        ));
    }

    private int getRegisteredPlaceholderCount() {
        return getRegisteredPlaceholders().size();
    }
}
