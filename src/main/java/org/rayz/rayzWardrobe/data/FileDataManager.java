package org.rayz.rayzWardrobe.data;

import org.rayz.rayzWardrobe.RayzWardrobe.ArmorSet;
import org.rayz.rayzWardrobe.RayzWardrobe;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * File-based implementation of DataManager with atomic saves and safe error recovery.
 */
public class FileDataManager implements DataManager {
    private final RayzWardrobe plugin;

    public FileDataManager(RayzWardrobe plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<ArmorSet> loadPlayerArmorSets(UUID playerUUID) {
        List<ArmorSet> armorSets = new ArrayList<>();
        File dataFolder = new File(plugin.getDataFolder(), "data");

        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
            return armorSets;
        }

        File playerFile = new File(dataFolder, playerUUID.toString() + ".yml");
        if (!playerFile.exists()) {
            return armorSets;
        }

        try {
            YamlConfiguration playerConfig = YamlConfiguration.loadConfiguration(playerFile);
            Set<String> keys = playerConfig.getKeys(false);

            for (String key : keys) {
                try {
                    int idx = Integer.parseInt(key);
                    String name = playerConfig.getString(key + ".name");
                    ItemStack[] armor = new ItemStack[4];

                    for (int i = 0; i < 4; i++) {
                        armor[i] = playerConfig.getItemStack(key + ".armor." + i);
                    }

                    ItemStack[] tools = new ItemStack[2];
                    for (int i = 0; i < tools.length; i++) {
                        tools[i] = playerConfig.getItemStack(key + ".tools." + i);
                    }
                    ConfigurationSection legacyExtraSection = playerConfig.getConfigurationSection(key + ".loadout");
                    if (legacyExtraSection != null) {
                        int toolIndex = 0;
                        for (String legacyKey : legacyExtraSection.getKeys(false)) {
                            if (toolIndex >= tools.length) break;
                            ItemStack item = legacyExtraSection.getItemStack(legacyKey);
                            if (item != null) tools[toolIndex++] = item;
                        }
                    }

                    ArmorSet set = new ArmorSet(name != null ? name : ("Set #" + (idx + 1)), armor, tools);

                    while (armorSets.size() <= idx) {
                        armorSets.add(null);
                    }

                    armorSets.set(idx, set);
                } catch (NumberFormatException nfe) {
                    // ignore non-numeric keys
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load player data from " + playerFile.getName() + " for player " + playerUUID + ": " + e.getMessage(), e);
        }

        return armorSets;
    }

    @Override
    public void savePlayerArmorSets(UUID playerUUID, List<ArmorSet> armorSets) {
        File dataFolder = new File(plugin.getDataFolder(), "data");
        File playerFile = new File(dataFolder, playerUUID.toString() + ".yml");
        YamlConfiguration playerConfig = new YamlConfiguration();

        int lastIndex = -1;
        if (armorSets != null) {
            for (int i = armorSets.size() - 1; i >= 0; i--) {
                if (armorSets.get(i) != null) {
                    lastIndex = i;
                    break;
                }
            }
        }

        for (int i = 0; i <= lastIndex; i++) {
            ArmorSet set = armorSets.get(i);
            if (set == null) continue;

            playerConfig.set(i + ".name", set.getName());
            ItemStack[] armor = set.getArmor();
            for (int j = 0; j < 4; j++) {
                playerConfig.set(i + ".armor." + j, (armor != null && j < armor.length) ? armor[j] : null);
            }

            ItemStack[] tools = set.getTools();
            for (int j = 0; j < 2; j++) {
                playerConfig.set(i + ".tools." + j, (tools != null && j < tools.length) ? tools[j] : null);
            }
        }

        safeSaveYaml(playerFile, playerConfig, "armor sets for player " + playerUUID);
    }

    @Override
    public String loadPlayerTheme(UUID playerUUID) {
        File dataFolder = new File(plugin.getDataFolder(), "playerdata");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
            return "classic";
        }

        File themeFile = new File(dataFolder, playerUUID.toString() + ".yml");
        if (!themeFile.exists()) {
            return "classic";
        }

        try {
            YamlConfiguration themeConfig = YamlConfiguration.loadConfiguration(themeFile);
            return themeConfig.getString("selected-theme", "classic");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load theme for player " + playerUUID + ": " + e.getMessage());
            return "classic";
        }
    }

    @Override
    public void savePlayerTheme(UUID playerUUID, String themeKey) {
        File dataFolder = new File(plugin.getDataFolder(), "playerdata");
        File themeFile = new File(dataFolder, playerUUID.toString() + ".yml");
        YamlConfiguration themeConfig = new YamlConfiguration();
        themeConfig.set("selected-theme", themeKey == null || themeKey.isBlank() ? "classic" : themeKey);
        safeSaveYaml(themeFile, themeConfig, "theme for player " + playerUUID);
    }

    private void safeSaveYaml(File targetFile, YamlConfiguration config, String logContext) {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tempFile = new File(parent, targetFile.getName() + ".tmp");
        try {
            config.save(tempFile);
            try {
                Files.move(tempFile.toPath(), targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicException) {
                Files.move(tempFile.toPath(), targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save data (" + logContext + "): " + e.getMessage(), e);
            if (tempFile.exists()) {
                try {
                    tempFile.delete();
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void close() {
        // Nothing to close for file system
    }
}
