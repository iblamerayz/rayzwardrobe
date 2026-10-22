package org.rayz.rayzWardrobe.data;

import org.bukkit.entity.Player;
import org.rayz.rayzWardrobe.RayzWardrobe.ArmorSet;

import java.util.List;
import java.util.UUID;

/**
 * Interface for data storage management
 */
public interface DataManager {
    // Armor set operations
    List<ArmorSet> loadPlayerArmorSets(UUID playerUUID);
    void savePlayerArmorSets(UUID playerUUID, List<ArmorSet> armorSets);

    // Theme operations
    String loadPlayerTheme(UUID playerUUID);
    void savePlayerTheme(UUID playerUUID, String themeKey);

    // Check if storage is ready
    boolean isReady();

    // Close connections
    void close();
}