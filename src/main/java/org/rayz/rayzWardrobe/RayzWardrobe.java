package org.rayz.rayzWardrobe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.rayz.rayzWardrobe.data.DataManager;
import org.rayz.rayzWardrobe.data.FileDataManager;
import org.rayz.rayzWardrobe.data.MySQLDataManager;
import org.rayz.rayzWardrobe.hooks.HookManager;
import org.rayz.rayzWardrobe.hooks.ItemProvider;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class RayzWardrobe extends JavaPlugin implements Listener {

    private static final int BSTATS_PLUGIN_ID = 31150;
    private static final List<String> DEFAULT_TOOL_MATERIALS = Arrays.asList(
            "*_SWORD", "*_AXE", "*_PICKAXE", "*_SHOVEL", "*_HOE",
            "BOW", "CROSSBOW", "TRIDENT", "MACE", "SHIELD"
    );

    private static final Map<String, Sound> SOUND_MAP = Map.of(
            "BLOCK_CHEST_OPEN", Sound.BLOCK_CHEST_OPEN,
            "ENTITY_PLAYER_LEVELUP", Sound.ENTITY_PLAYER_LEVELUP,
            "ITEM_ARMOR_EQUIP_NETHERITE", Sound.ITEM_ARMOR_EQUIP_NETHERITE,
            "ENTITY_EXPERIENCE_ORB_PICKUP", Sound.ENTITY_EXPERIENCE_ORB_PICKUP
    );

    private FileConfiguration config;
    private FileConfiguration messages;
    private FileConfiguration guiConfig;

    private File configFile;
    private File messagesFile;
    private File guiFile;

    private DataManager dataManager;
    private MySQLManager mySQLManager;
    private Object placeholderExpansion;
    private Object metrics;

    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> actionDebounce = new ConcurrentHashMap<>();
    private final Map<UUID, String> renamingPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> renamingSlots = new ConcurrentHashMap<>();
    private final Map<UUID, List<ArmorSet>> playerArmorSets = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> currentPages = new ConcurrentHashMap<>();
    private final Map<UUID, Inventory> openInventories = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> adminViewTargets = new ConcurrentHashMap<>();

    private final Map<UUID, String> playerThemes = new ConcurrentHashMap<>();
    private final Map<String, Theme> themeRegistry = new LinkedHashMap<>();
    private final Map<UUID, Inventory> openThemeInventories = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> themeAnimationTasks = new ConcurrentHashMap<>();
    private boolean toolStorageEnabled;
    private int maxStoredTools = 2;
    private Material emptyToolIcon = Material.IRON_PICKAXE;
    private final List<String> allowedToolMaterials = new ArrayList<>();
    private final List<String> allowedCustomTools = new ArrayList<>();
    private final List<String> allowedPersistentDataTools = new ArrayList<>();

    private static final Material[] SPECTRUM = {
            Material.RED_STAINED_GLASS_PANE,
            Material.LIME_STAINED_GLASS_PANE,
            Material.BLUE_STAINED_GLASS_PANE,
            Material.PURPLE_STAINED_GLASS_PANE
    };

    @Override
    public void onEnable() {
        createFiles();
        loadToolConfiguration();
        createThemeFiles();

        if (messages == null || guiConfig == null) {
            getLogger().severe("Disabling plugin - required configuration files could not be loaded!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        HookManager.init();
        initializeDataManager();

        getServer().getPluginManager().registerEvents(this, this);
        loadPlayerData();
        loadThemes();
        loadPlayerThemes();
        registerPlaceholderExpansion();
        initializeMetrics();

        String version = getDescription().getVersion();
        String enableMsgRaw = getRawMessage("console.enable", "&a[RayzWardrobe] &fEnabled! &b(v{version})").replace("{version}", version);
        getServer().getConsoleSender().sendMessage(colorize(enableMsgRaw));
    }

    @Override
    public void onDisable() {
        closeAllActiveGuis();

        for (BukkitRunnable task : themeAnimationTasks.values()) {
            try { task.cancel(); } catch (Exception ignored) {}
        }
        themeAnimationTasks.clear();

        unregisterPlaceholderExpansion();
        shutdownMetrics();

        savePlayerDataSync();
        savePlayerThemesSync();

        if (dataManager != null) {
            try {
                dataManager.close();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error closing DataManager on disable: " + e.getMessage(), e);
            }
        }

        String disableMsgRaw = getRawMessage("console.disable", "&c[RayzWardrobe] &fSaving all data...");
        getServer().getConsoleSender().sendMessage(colorize(disableMsgRaw));
    }

    private void closeAllActiveGuis() {
        Set<UUID> playersToClose = new HashSet<>();
        playersToClose.addAll(openInventories.keySet());
        playersToClose.addAll(openThemeInventories.keySet());

        for (UUID uuid : playersToClose) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                try {
                    player.closeInventory();
                } catch (Exception ignored) {}
            }
        }
        openInventories.clear();
        openThemeInventories.clear();
        adminViewTargets.clear();
    }

    private void initializeMetrics() {
        try {
            Class<?> metricsClass = Class.forName("org.bstats.bukkit.Metrics");
            metrics = metricsClass.getConstructor(org.bukkit.plugin.Plugin.class, int.class).newInstance(this, BSTATS_PLUGIN_ID);
            getLogger().info("bStats metrics enabled with plugin ID " + BSTATS_PLUGIN_ID + ".");
        } catch (ReflectiveOperationException | LinkageError e) {
            getLogger().warning("bStats metrics could not be enabled because Metrics is missing from the plugin jar: " + e.getMessage());
        }
    }

    private void shutdownMetrics() {
        if (metrics == null) {
            return;
        }
        try {
            metrics.getClass().getMethod("shutdown").invoke(metrics);
        } catch (ReflectiveOperationException | LinkageError e) {
            getLogger().warning("bStats metrics could not be shut down cleanly: " + e.getMessage());
        } finally {
            metrics = null;
        }
    }

    private void registerPlaceholderExpansion() {
        if (config != null && !config.getBoolean("placeholderapi.enabled", true)) {
            return;
        }
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().info("PlaceholderAPI not found; RayzWardrobe placeholders are disabled.");
            return;
        }
        try {
            Class<?> expansionClass = Class.forName("org.rayz.rayzWardrobe.WardrobePlaceholderExpansion");
            placeholderExpansion = expansionClass.getConstructor(RayzWardrobe.class).newInstance(this);
            boolean registered = (Boolean) expansionClass.getMethod("register").invoke(placeholderExpansion);
            if (registered) {
                getLogger().info("Registered PlaceholderAPI placeholders with identifier %rayzwardrobe_*%.");
            } else {
                getLogger().warning("PlaceholderAPI refused to register RayzWardrobe placeholders.");
            }
        } catch (ReflectiveOperationException | LinkageError error) {
            placeholderExpansion = null;
            getLogger().warning("PlaceholderAPI classes were unavailable; placeholders are disabled. " + error.getClass().getSimpleName());
        }
    }

    private void unregisterPlaceholderExpansion() {
        if (placeholderExpansion == null) return;
        try {
            placeholderExpansion.getClass().getMethod("unregister").invoke(placeholderExpansion);
        } catch (ReflectiveOperationException ignored) {
        } finally {
            placeholderExpansion = null;
        }
    }

    private void initializeDataManager() {
        boolean useMySQL = config != null && config.getBoolean("mysql.enabled", false);
        if (useMySQL) {
            mySQLManager = new MySQLManager(this);
            if (mySQLManager.connect()) {
                dataManager = new MySQLDataManager(mySQLManager);
                getLogger().info("Using MySQL for data storage");
            } else {
                dataManager = new FileDataManager(this);
                getLogger().warning("MySQL connection failed, falling back to file storage");
            }
        } else {
            dataManager = new FileDataManager(this);
            getLogger().info("Using file system for data storage");
        }
    }

    private void createFiles() {
        configFile = new File(getDataFolder(), "config.yml");
        messagesFile = new File(getDataFolder(), "messages_en.yml");
        guiFile = new File(getDataFolder(), "gui.yml");
        File legacyGuiFile = new File(getDataFolder(), "GUI.yml");

        if (!configFile.exists()) { configFile.getParentFile().mkdirs(); saveResource("config.yml", false); }
        if (!messagesFile.exists()) { messagesFile.getParentFile().mkdirs(); saveResource("messages_en.yml", false); }

        if (!guiFile.exists() && legacyGuiFile.exists()) {
            guiFile = legacyGuiFile;
        }

        if (!guiFile.exists()) {
            guiFile.getParentFile().mkdirs();
            try {
                saveResource("GUI.yml", false);
                if (legacyGuiFile.exists() && !legacyGuiFile.equals(guiFile)) {
                    try {
                        Files.move(legacyGuiFile.toPath(), guiFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception moveError) {
                        guiFile = legacyGuiFile;
                    }
                }
            } catch (IllegalArgumentException e) {
                getLogger().warning("gui.yml was missing from the JAR! Generating a fallback default...");
                generateFallbackGuiFile();
            }
        }

        File themesFolder = new File(getDataFolder(), "themes");
        if (!themesFolder.exists()) themesFolder.mkdirs();

        File playerData = new File(getDataFolder(), "playerdata");
        if (!playerData.exists()) playerData.mkdirs();

        File dataFolder = new File(getDataFolder(), "data");
        if (!dataFolder.exists()) dataFolder.mkdirs();

        config = loadConfigurationWithDefaults(configFile, "config.yml");
        messages = loadConfigurationWithDefaults(messagesFile, "messages_en.yml");
        guiConfig = loadConfigurationWithDefaults(guiFile, "GUI.yml");
    }

    private YamlConfiguration loadConfigurationWithDefaults(File file, String resourceName) {
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        InputStream resource = getResource(resourceName);
        if (resource == null) {
            return loaded;
        }

        try (InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(reader));
        } catch (Exception error) {
            getLogger().warning("Could not load default values for " + resourceName + ": " + error.getMessage());
        }
        return loaded;
    }

    private void generateFallbackGuiFile() {
        try {
            if (guiFile.createNewFile()) {
                java.io.FileWriter writer = new java.io.FileWriter(guiFile);
                writer.write("inventory:\n");
                writer.write("  size: 54\n");
                writer.write("  sets-per-page: 7\n");
                writer.write("  items:\n");
                writer.write("    previous-page:\n      slot: 45\n");
                writer.write("    next-page:\n      slot: 53\n");
                writer.write("    page-info:\n      slot: 49\n");
                writer.write("    themes-button:\n      slot: 8\n");
                writer.write("    admin-back-button:\n      slot: 8\n");
                writer.write("  set-layout:\n");

                for (int i = 1; i <= 7; i++) {
                    writer.write("    " + i + ":\n");
                    writer.write("      nameplate: " + i + "\n");
                    writer.write("      helmet: " + (i + 9) + "\n");
                    writer.write("      chestplate: " + (i + 18) + "\n");
                    writer.write("      leggings: " + (i + 27) + "\n");
                    writer.write("      boots: " + (i + 36) + "\n");
                    writer.write("      action: " + (i + 45) + "\n");
                }
                writer.close();
            }
        } catch (java.io.IOException ex) {
            getLogger().severe("Could not generate fallback gui.yml! " + ex.getMessage());
        }
    }

    private void createThemeFiles() {
        File themesFolder = new File(getDataFolder(), "themes");
        if (!themesFolder.exists()) themesFolder.mkdirs();
        String[] defaults = new String[] {
                "classic.yml", "cyber.yml", "darkmode.yml", "galaxy.yml",
                "neon.yml", "pastel.yml", "royal.yml", "sunset.yml"
        };
        for (String name : defaults) {
            try {
                File out = new File(getDataFolder(), "themes/" + name);
                if (!out.exists()) {
                    saveResource("themes/" + name, false);
                }
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void reloadFiles() {
        config = loadConfigurationWithDefaults(configFile, "config.yml");
        messages = loadConfigurationWithDefaults(messagesFile, "messages_en.yml");
        guiConfig = loadConfigurationWithDefaults(guiFile, "GUI.yml");
        loadToolConfiguration();
        HookManager.init();
        loadThemes();
        initializeDataManager();
    }

    private void loadToolConfiguration() {
        allowedToolMaterials.clear();
        allowedCustomTools.clear();
        allowedPersistentDataTools.clear();
        toolStorageEnabled = config != null && config.getBoolean("tools.enabled", false);
        maxStoredTools = config != null ? clamp(config.getInt("tools.max-per-armor-set", 2), 0, 2) : 2;
        emptyToolIcon = matOrDefault(config != null ? config.getString("tools.empty-slot-icon", "IRON_PICKAXE") : "IRON_PICKAXE", Material.IRON_PICKAXE);

        if (!toolStorageEnabled || maxStoredTools <= 0) {
            toolStorageEnabled = false;
            if (config != null && !config.isSet("tools.enabled")) {
                getLogger().info("Tool storage is disabled. Add tools.enabled: true to config.yml to save up to 2 hand tools with each wardrobe set.");
            }
            return;
        }

        allowedToolMaterials.addAll(config.getStringList("tools.allowed-materials"));
        if (allowedToolMaterials.isEmpty()) {
            allowedToolMaterials.addAll(DEFAULT_TOOL_MATERIALS);
        }
        allowedCustomTools.addAll(config.getStringList("tools.allowed-custom-items"));
        allowedPersistentDataTools.addAll(config.getStringList("tools.allowed-persistent-data"));
        if (allowedPersistentDataTools.isEmpty()) {
            allowedPersistentDataTools.add("ecoitems:*");
            allowedPersistentDataTools.add("ecoarmor:*");
            allowedPersistentDataTools.add("eco:*");
        }
        getLogger().info("Tool storage enabled for up to " + maxStoredTools + " tool(s) per wardrobe set.");
    }

    private String getRawMessage(String path, String def) {
        return messages != null ? messages.getString(path, def) : def;
    }

    private void loadPlayerData() {
        if (dataManager == null) return;
        int count = playerArmorSets.size();
        String loadedMsgRaw = getRawMessage("console.loaded-data", "&7Loaded data for &b{count} &7players").replace("{count}", String.valueOf(count));
        getServer().getConsoleSender().sendMessage(colorize(loadedMsgRaw));
    }

    private void savePlayerDataSync() {
        if (dataManager == null) return;
        for (Map.Entry<UUID, List<ArmorSet>> entry : playerArmorSets.entrySet()) {
            try {
                dataManager.savePlayerArmorSets(entry.getKey(), copyArmorSetList(entry.getValue()));
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to save player data for " + entry.getKey() + ": " + e.getMessage());
            }
        }
    }

    private void savePlayerData(UUID uuid) {
        if (dataManager == null || uuid == null) return;
        List<ArmorSet> sets = playerArmorSets.get(uuid);
        if (sets == null) return;

        List<ArmorSet> snapshot = copyArmorSetList(sets);
        if (getServer().isPrimaryThread() && isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    dataManager.savePlayerArmorSets(uuid, snapshot);
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Failed to save player data for " + uuid + ": " + e.getMessage());
                }
            });
        } else {
            try {
                dataManager.savePlayerArmorSets(uuid, snapshot);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to save player data for " + uuid + ": " + e.getMessage());
            }
        }
    }

    private List<ArmorSet> copyArmorSetList(List<ArmorSet> source) {
        List<ArmorSet> copy = new ArrayList<>();
        if (source != null) {
            for (ArmorSet set : source) {
                if (set == null) {
                    copy.add(null);
                } else {
                    copy.add(new ArmorSet(set.getName(), set.getArmor(), set.getTools()));
                }
            }
        }
        return copy;
    }

    private void loadThemes() {
        themeRegistry.clear();
        File themesFolder = new File(getDataFolder(), "themes");
        if (!themesFolder.exists()) themesFolder.mkdirs();
        File[] files = themesFolder.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (!f.getName().endsWith(".yml")) continue;
            try {
                FileConfiguration yml = YamlConfiguration.loadConfiguration(f);
                String key = f.getName().replace(".yml", "");
                Theme t = new Theme();
                t.key = key;
                t.name = yml.getString("theme-name", key);
                t.slot = yml.getInt("slot", 10);
                t.permission = yml.getString("permission", "");
                t.icon = matOrDefault(yml.getString("icon", "BOOK"), Material.BOOK);
                t.background = matOrDefault(yml.getString("background", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
                t.primary = matOrDefault(yml.getString("primary-color", "WHITE_STAINED_GLASS_PANE"), Material.WHITE_STAINED_GLASS_PANE);
                t.secondary = matOrDefault(yml.getString("secondary-color", "LIGHT_BLUE_STAINED_GLASS_PANE"), Material.LIGHT_BLUE_STAINED_GLASS_PANE);
                t.emptySlotIcon = matOrDefault(yml.getString("empty-slot-icon", ""), null);
                t.lockedIcon = matOrDefault(yml.getString("locked-icon", "BARRIER"), Material.BARRIER);
                themeRegistry.put(key.toLowerCase(Locale.ROOT), t);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to load theme file " + f.getName() + ": " + e.getMessage());
            }
        }

        if (themeRegistry.isEmpty()) {
            Theme def = new Theme();
            def.key = "classic"; def.name = "Classic"; def.slot = 10;
            def.icon = Material.BOOK; def.background = Material.GRAY_STAINED_GLASS_PANE;
            def.primary = Material.WHITE_STAINED_GLASS_PANE; def.secondary = Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            def.lockedIcon = Material.BARRIER;
            themeRegistry.put(def.key, def);
        }
    }

    private void loadPlayerThemes() {}

    private void savePlayerThemesSync() {
        if (dataManager == null) return;
        for (Map.Entry<UUID, String> entry : playerThemes.entrySet()) {
            try { dataManager.savePlayerTheme(entry.getKey(), entry.getValue()); } catch (Exception ignored) {}
        }
    }

    private void savePlayerTheme(UUID uuid) {
        if (dataManager == null || uuid == null) return;
        String themeKey = playerThemes.getOrDefault(uuid, "classic");
        if (getServer().isPrimaryThread() && isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try { dataManager.savePlayerTheme(uuid, themeKey); } catch (Exception ignored) {}
            });
        } else {
            try { dataManager.savePlayerTheme(uuid, themeKey); } catch (Exception ignored) {}
        }
    }

    private Theme getSelectedTheme(Player player) {
        UUID uuid = player.getUniqueId();
        if (!playerThemes.containsKey(uuid) && dataManager != null) {
            String themeKey = dataManager.loadPlayerTheme(uuid);
            playerThemes.put(uuid, themeKey);
        }
        String key = playerThemes.getOrDefault(uuid, "classic");
        Theme t = themeRegistry.get(key.toLowerCase(Locale.ROOT));
        if (t == null) return themeRegistry.values().iterator().next();
        return t;
    }

    private boolean hasSlotPermission(Player player, int zeroBasedSlot) {
        if (config == null || !config.getBoolean("settings.slot-permissions-enabled", false)) return true;
        if (zeroBasedSlot < 0) return false;
        if (player.hasPermission("rayzwardrobe.admin")
                || player.hasPermission("rayzwardrobe.use.slot.*")
                || player.hasPermission("rayzwardrobe.slot.*")) {
            return true;
        }

        int globalSlot = zeroBasedSlot + 1;
        int setsPerPage = guiConfig != null ? Math.max(1, guiConfig.getInt("inventory.sets-per-page", 7)) : 7;
        int columnSlot = (zeroBasedSlot % setsPerPage) + 1;
        String scope = config.getString("settings.slot-permission-scope", "column").toLowerCase(Locale.ROOT);

        if (scope.equals("global")) {
            return hasSpecificSlotPermission(player, globalSlot);
        }
        if (scope.equals("both")) {
            return hasSpecificSlotPermission(player, globalSlot) || hasSpecificSlotPermission(player, columnSlot);
        }
        return hasSpecificSlotPermission(player, columnSlot);
    }

    private boolean hasSpecificSlotPermission(Player player, int displaySlot) {
        return player.hasPermission("rayzwardrobe.use.slot" + displaySlot)
                || player.hasPermission("rayzwardrobe.use.slot." + displaySlot)
                || player.hasPermission("rayzwardrobe.slot" + displaySlot)
                || player.hasPermission("rayzwardrobe.slot." + displaySlot);
    }

    private boolean canViewSlot(Player player, int slot) {
        if (player.hasPermission("rayzwardrobe.admin")) return true;
        return hasSlotPermission(player, slot);
    }

    private boolean hasPagePermission(Player player, int page) {
        if (page <= 1 || config == null || !config.getBoolean("settings.page-permissions-enabled", false)) return true;
        return player.hasPermission("rayzwardrobe.use.page" + page)
                || player.hasPermission("rayzwardrobe.use.page." + page)
                || player.hasPermission("rayzwardrobe.page" + page)
                || player.hasPermission("rayzwardrobe.page." + page)
                || player.hasPermission("rayzwardrobe.use.page.*")
                || player.hasPermission("rayzwardrobe.page.*")
                || player.hasPermission("rayzwardrobe.admin");
    }

    private int getMaxPages() {
        int maxSets = config != null ? config.getInt("settings.max-armor-sets", 56) : 56;
        int setsPerPage = guiConfig != null ? Math.max(1, guiConfig.getInt("inventory.sets-per-page", 7)) : 7;
        return Math.max(1, (int) Math.ceil((double) maxSets / setsPerPage));
    }

    private int getInventorySize() {
        int configuredSize = guiConfig != null ? guiConfig.getInt("inventory.size", 54) : 54;
        if (configuredSize < 9) return 9;
        if (configuredSize > 54) return 54;
        return Math.max(9, ((configuredSize + 8) / 9) * 9);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("wardrobe")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(colorize(getRawMessage("errors.only-players", "&c✖ &7Only players can use &bRayzWardrobe&7!")));
                return true;
            }
            Player player = (Player) sender;

            if (args.length == 0) {
                if (!player.hasPermission("rayzwardrobe.use")) {
                    player.sendMessage(colorize(getRawMessage("errors.no-permission", "&c✖ &7You need &b{permission} &7permission!").replace("{permission}", "rayzwardrobe.use")));
                    return true;
                }
                if (cooldowns.containsKey(player.getUniqueId())) {
                    long secondsLeft = ((cooldowns.get(player.getUniqueId()) / 1000) + config.getInt("settings.cooldown-seconds", 0)) - (System.currentTimeMillis() / 1000);
                    if (secondsLeft > 0) {
                        player.sendMessage(colorize(getRawMessage("errors.cooldown", "&c⏳ &7Please wait &b{time}s &7before using again!").replace("{time}", String.valueOf(secondsLeft))));
                        return true;
                    }
                }
                cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
                openWardrobeGUI(player, 1);
                return true;
            }

            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "credits":
                    sender.sendMessage(colorize("&6&l⚡ &bRayzWardrobe &7- &fPremium Armor Wardrobe"));
                    sender.sendMessage(colorize("&7Created by: &bRayz"));
                    sender.sendMessage(colorize("&7Reports and supports on discord:"));
                    sender.sendMessage(colorize("&9https://discord.gg/9YJkt2ESrU"));
                    sender.sendMessage(colorize("&7Version: &b" + getDescription().getVersion()));
                    return true;
                case "themes":
                    if (!player.hasPermission("rayzwardrobe.themes")) {
                        player.sendMessage(colorize(getRawMessage("errors.no-permission", "&c✖ &7You need &b{permission} &7permission!").replace("{permission}", "rayzwardrobe.themes")));
                        return true;
                    }
                    openThemesGUI(player);
                    return true;
                case "help":
                    sendHelp(player);
                    return true;
                case "reload":
                    return handleReload(sender);
                case "view":
                    if (!sender.hasPermission("rayzwardrobe.admin.view")) {
                        sender.sendMessage(colorize(getRawMessage("errors.no-permission", "&c✖ &7You need &b{permission} &7permission!").replace("{permission}", "rayzwardrobe.admin.view")));
                        return true;
                    }
                    if (args.length < 2) {
                        sender.sendMessage(colorize("&cUsage: /wardrobe view <player> [page]"));
                        return true;
                    }
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null) {
                        sender.sendMessage(colorize("&cPlayer not found or offline!"));
                        return true;
                    }
                    int page = 1;
                    if (args.length > 2) { try { page = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) {} }
                    openAdminWardrobeGUI(player, target, page);
                    return true;
                default:
                    sender.sendMessage(colorize("&cUnknown subcommand! Use &b/wardrobe &7or &b/wardrobe credits/themes/view"));
                    return true;
            }
        }

        if (cmd.getName().equalsIgnoreCase("rwreload")) {
            if (!sender.hasPermission("rayzwardrobe.reload")) {
                sender.sendMessage(colorize(getRawMessage("errors.no-permission", "&c✖ &7You need &b{permission} &7permission!").replace("{permission}", "rayzwardrobe.reload")));
                return true;
            }
            return handleReload(sender);
        }

        return false;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("rayzwardrobe.reload")) {
            sender.sendMessage(colorize(getRawMessage("errors.no-permission", "&c✖ &7You need &b{permission} &7permission!").replace("{permission}", "rayzwardrobe.reload")));
            return true;
        }

        closeAllActiveGuis();

        for (BukkitRunnable task : themeAnimationTasks.values()) {
            try { task.cancel(); } catch (Exception ignored) {}
        }
        themeAnimationTasks.clear();

        savePlayerDataSync();
        savePlayerThemesSync();
        unregisterPlaceholderExpansion();

        if (dataManager != null) {
            try {
                dataManager.close();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error closing DataManager on reload: " + e.getMessage(), e);
            }
        }

        reloadFiles();
        registerPlaceholderExpansion();

        sender.sendMessage(colorize(getRawMessage("messages.reload", "&a[RayzWardrobe] &fConfiguration, GUI, messages & themes reloaded successfully!")));
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(colorize("&6&lRayzWardrobe &8- &7Commands"));
        sender.sendMessage(colorize("&e/wardrobe &8- &7Open your wardrobe"));
        sender.sendMessage(colorize("&e/wardrobe themes &8- &7Open the theme selector"));
        sender.sendMessage(colorize("&e/wardrobe credits &8- &7Show plugin credits"));
        if (sender.hasPermission("rayzwardrobe.admin.view")) sender.sendMessage(colorize("&e/wardrobe view <player> [page] &8- &7View a player's wardrobe"));
        if (sender.hasPermission("rayzwardrobe.reload")) sender.sendMessage(colorize("&e/wardrobe reload &8- &7Reload configuration and storage"));
    }

    private void openWardrobeGUI(Player player, int requestedPage) {
        UUID uuid = player.getUniqueId();
        if (!playerArmorSets.containsKey(uuid) && dataManager != null) {
            List<ArmorSet> sets = dataManager.loadPlayerArmorSets(uuid);
            playerArmorSets.put(uuid, sets);
        }

        if (!hasPagePermission(player, requestedPage)) {
            player.sendMessage(colorize(getRawMessage("errors.no-page-permission", "&c✖ &7You don't have permission to access page &b{page}&7!").replace("{page}", String.valueOf(requestedPage))));
            return;
        }

        int maxPages = getMaxPages();
        int page = Math.max(1, Math.min(requestedPage, maxPages));
        currentPages.put(player.getUniqueId(), page);

        List<ArmorSet> armorSets = playerArmorSets.getOrDefault(player.getUniqueId(), new ArrayList<>());
        Theme theme = getSelectedTheme(player);

        String titleRaw = getRawMessage("gui.title", "&6&l⚡ &bRayz&3Wardrobe &8| &7Page {page}/{total}")
                .replace("{page}", String.valueOf(page))
                .replace("{total}", String.valueOf(maxPages));

        int invSize = getInventorySize();
        Inventory gui = Bukkit.createInventory(player, invSize, colorize(titleRaw));

        Material themedBackground = theme.background != null ? theme.background : Material.GRAY_STAINED_GLASS_PANE;
        ItemStack background = new ItemStack(guiConfig.getBoolean("inventory.items.background.use-theme", true)
                ? themedBackground
                : guiMaterial("inventory.items.background.material", themedBackground));
        ItemMeta bgMeta = background.getItemMeta();
        if (bgMeta != null) { bgMeta.displayName(Component.space()); background.setItemMeta(bgMeta); }
        for (int i = 0; i < invSize; i++) gui.setItem(i, background);

        int prevSlot = guiConfig.getInt("inventory.items.previous-page.slot", -1);
        if (prevSlot != -1) {
            ItemStack prevPage = buildGuiItem(
                    guiMaterial(page > 1 ? "inventory.items.previous-page.material" : "inventory.items.previous-page.disabled-material", page > 1 ? Material.ARROW : Material.BARRIER),
                    getRawMessage(page > 1 ? "gui.buttons.previous" : "gui.buttons.no-previous", page > 1 ? "&aPrevious Page" : "&cNo Previous Page"),
                    Collections.emptyList());
            gui.setItem(prevSlot, prevPage);
        }

        int nextSlot = guiConfig.getInt("inventory.items.next-page.slot", -1);
        if (nextSlot != -1) {
            ItemStack nextPage = buildGuiItem(
                    guiMaterial(page < maxPages ? "inventory.items.next-page.material" : "inventory.items.next-page.disabled-material", page < maxPages ? Material.ARROW : Material.BARRIER),
                    getRawMessage(page < maxPages ? "gui.buttons.next" : "gui.buttons.no-next", page < maxPages ? "&aNext Page" : "&cNo Next Page"),
                    Collections.emptyList());
            gui.setItem(nextSlot, nextPage);
        }

        int infoSlot = guiConfig.getInt("inventory.items.page-info.slot", -1);
        if (infoSlot != -1) {
            Map<String, String> tokens = placeholders("page", page, "total", maxPages);
            ItemStack pageInfo = buildGuiItem(guiMaterial("inventory.items.page-info.material", Material.BOOK),
                    applyPlaceholders(getRawMessage("gui.buttons.page-info", "&bPage &e{page} &bof &e{total}"), tokens),
                    configuredLore("gui.lore.page-info", tokens));
            gui.setItem(infoSlot, pageInfo);
        }

        int themeSlot = guiConfig.getInt("inventory.items.themes-button.slot", -1);
        if (themeSlot != -1) {
            Map<String, String> tokens = placeholders("theme", theme.name);
            ItemStack themeBtn = buildGuiItem(guiMaterial("inventory.items.themes-button.material", theme.icon != null ? theme.icon : Material.BOOK),
                    applyPlaceholders(getRawMessage("gui.buttons.themes", "&dThemes &7(Selected: &f{theme}&7)"), tokens),
                    configuredLore("gui.lore.themes", tokens));
            gui.setItem(themeSlot, themeBtn);
        }

        int setsPerPage = guiConfig.getInt("inventory.sets-per-page", 7);
        int startIndex = (page - 1) * setsPerPage;

        for (int slotIndex = 1; slotIndex <= setsPerPage; slotIndex++) {
            int globalIndex = startIndex + (slotIndex - 1);
            ArmorSet set = (globalIndex < armorSets.size() ? armorSets.get(globalIndex) : null);
            boolean canView = canViewSlot(player, globalIndex);

            String prefix = "inventory.set-layout." + slotIndex + ".";
            int nameSlot = guiConfig.getInt(prefix + "nameplate", -1);
            int helmSlot = guiConfig.getInt(prefix + "helmet", -1);
            int chestSlot = guiConfig.getInt(prefix + "chestplate", -1);
            int legsSlot = guiConfig.getInt(prefix + "leggings", -1);
            int bootsSlot = guiConfig.getInt(prefix + "boots", -1);
            int actionSlot = guiConfig.getInt(prefix + "action", -1);

            if (nameSlot != -1) {
                ItemStack nameplate = new ItemStack(canView
                        ? guiMaterial(set != null ? "inventory.items.nameplate.material" : "inventory.items.nameplate.empty-material", Material.NAME_TAG)
                        : guiMaterial("inventory.items.nameplate.locked-material", Material.BARRIER));
                ItemMeta nm = nameplate.getItemMeta();
                if (nm != null) {
                    Map<String, String> tokens = placeholders("name", set != null ? set.getName() : "", "slot", globalIndex + 1);
                    nm.displayName(colorize(canView ? (set != null ? "&f" + set.getName() : getRawMessage("gui.buttons.empty-slot", "&7Empty armour slot")) : getRawMessage("gui.buttons.locked-slot", "&cLocked Slot")));
                    List<Component> lore = canView ? configuredLore(set != null ? "gui.lore.nameplate" : "gui.lore.empty-slot", tokens) : configuredLore("gui.lore.locked-slot", tokens);
                    if (canView) lore.addAll(buildToolLore(set));
                    if (!lore.isEmpty()) nm.lore(lore);
                    nameplate.setItemMeta(nm);
                }
                gui.setItem(nameSlot, nameplate);
            }

            Material placeholderColor = theme.emptySlotIcon != null ? theme.emptySlotIcon : (theme.primary != null ? theme.primary : SPECTRUM[globalIndex % SPECTRUM.length]);
            ItemStack locked = buildGuiItem(guiMaterial("inventory.items.armor.locked-material", Material.BARRIER),
                    getRawMessage("gui.buttons.locked-armor", "&cLocked"),
                    Collections.emptyList());

            if (helmSlot != -1) gui.setItem(helmSlot, canView ? (set != null ? defaultIfNull(set.getArmor()[3]) : emptyArmorPlaceholder(placeholderColor)) : locked);
            if (chestSlot != -1) gui.setItem(chestSlot, canView ? (set != null ? defaultIfNull(set.getArmor()[2]) : emptyArmorPlaceholder(placeholderColor)) : locked);
            if (legsSlot != -1) gui.setItem(legsSlot, canView ? (set != null ? defaultIfNull(set.getArmor()[1]) : emptyArmorPlaceholder(placeholderColor)) : locked);
            if (bootsSlot != -1) gui.setItem(bootsSlot, canView ? (set != null ? defaultIfNull(set.getArmor()[0]) : emptyArmorPlaceholder(placeholderColor)) : locked);
            renderToolPreviewSlots(gui, slotIndex, set, canView, locked);

            if (actionSlot != -1) {
                ItemStack actionBtn;
                if (set != null && canView) {
                    actionBtn = new ItemStack(guiMaterial("inventory.items.actions.equip-material", Material.LIME_DYE));
                    ItemMeta meta = actionBtn.getItemMeta();
                    if (meta != null) {
                        Map<String, String> tokens = placeholders("name", set.getName(), "slot", globalIndex + 1);
                        meta.displayName(colorize(applyPlaceholders(getRawMessage("gui.buttons.equip", "&aEquip &7{name}"), tokens)));
                        List<Component> lore = configuredLore("gui.lore.equip", tokens);
                        lore.addAll(buildToolLore(set));
                        if (!lore.isEmpty()) meta.lore(lore);
                        actionBtn.setItemMeta(meta);
                    }
                } else if (canView) {
                    boolean toolMode = isToolStorageEnabled();
                    actionBtn = new ItemStack(guiMaterial(toolMode ? "inventory.items.actions.save-tools-material" : "inventory.items.actions.save-material", Material.GRAY_DYE));
                    ItemMeta meta = actionBtn.getItemMeta();
                    if (meta != null) {
                        Map<String, String> tokens = placeholders("slot", globalIndex + 1);
                        meta.displayName(colorize(applyPlaceholders(getRawMessage(toolMode ? "gui.buttons.save-tools" : "gui.buttons.save", toolMode ? "&aSave Armor + Tools Here" : "&aSave Armor Here"), tokens)));
                        List<Component> lore = configuredLore(toolMode ? "gui.lore.save-tools" : "gui.lore.save", tokens);
                        if (!lore.isEmpty()) meta.lore(lore);
                        actionBtn.setItemMeta(meta);
                    }
                } else {
                    actionBtn = buildGuiItem(guiMaterial("inventory.items.actions.locked-material", Material.BARRIER),
                            getRawMessage("gui.buttons.locked-action", "&cNo Permission"),
                            configuredLore("gui.lore.locked-action", placeholders("slot", globalIndex + 1)));
                }
                gui.setItem(actionSlot, actionBtn);
            }
        }

        player.openInventory(gui);
        openInventories.put(player.getUniqueId(), gui);
        playSound(player, "sounds.open");
    }

    private void openAdminWardrobeGUI(Player admin, Player target, int requestedPage) {
        UUID targetUUID = target.getUniqueId();
        UUID adminUUID = admin.getUniqueId();

        adminViewTargets.put(adminUUID, targetUUID);
        if (!playerArmorSets.containsKey(targetUUID) && dataManager != null) {
            playerArmorSets.put(targetUUID, dataManager.loadPlayerArmorSets(targetUUID));
        }

        int maxPages = getMaxPages();
        int page = Math.max(1, Math.min(requestedPage, maxPages));
        currentPages.put(adminUUID, page);

        List<ArmorSet> armorSets = playerArmorSets.getOrDefault(targetUUID, new ArrayList<>());
        Theme theme = getSelectedTheme(admin);

        String titleRaw = getRawMessage("gui.admin-title", "&6&l⚡ &b{player}'s Wardrobe &8| &7Page {page}/{total}")
                .replace("{player}", target.getName())
                .replace("{page}", String.valueOf(page))
                .replace("{total}", String.valueOf(maxPages));

        int invSize = getInventorySize();
        Inventory gui = Bukkit.createInventory(admin, invSize, colorize(titleRaw));

        Material themedBackground = theme.background != null ? theme.background : Material.GRAY_STAINED_GLASS_PANE;
        ItemStack background = new ItemStack(guiConfig.getBoolean("inventory.items.background.use-theme", true)
                ? themedBackground
                : guiMaterial("inventory.items.background.material", themedBackground));
        ItemMeta bgMeta = background.getItemMeta();
        if (bgMeta != null) { bgMeta.displayName(Component.space()); background.setItemMeta(bgMeta); }
        for (int i = 0; i < invSize; i++) gui.setItem(i, background);

        int prevSlot = guiConfig.getInt("inventory.items.previous-page.slot", -1);
        if (prevSlot != -1) {
            ItemStack prevPage = buildGuiItem(
                    guiMaterial(page > 1 ? "inventory.items.previous-page.material" : "inventory.items.previous-page.disabled-material", page > 1 ? Material.ARROW : Material.BARRIER),
                    getRawMessage(page > 1 ? "gui.buttons.previous" : "gui.buttons.no-previous", page > 1 ? "&aPrevious Page" : "&cNo Previous Page"),
                    Collections.emptyList());
            gui.setItem(prevSlot, prevPage);
        }

        int nextSlot = guiConfig.getInt("inventory.items.next-page.slot", -1);
        if (nextSlot != -1) {
            ItemStack nextPage = buildGuiItem(
                    guiMaterial(page < maxPages ? "inventory.items.next-page.material" : "inventory.items.next-page.disabled-material", page < maxPages ? Material.ARROW : Material.BARRIER),
                    getRawMessage(page < maxPages ? "gui.buttons.next" : "gui.buttons.no-next", page < maxPages ? "&aNext Page" : "&cNo Next Page"),
                    Collections.emptyList());
            gui.setItem(nextSlot, nextPage);
        }

        int infoSlot = guiConfig.getInt("inventory.items.page-info.slot", -1);
        if (infoSlot != -1) {
            Map<String, String> tokens = placeholders("page", page, "total", maxPages);
            ItemStack pageInfo = buildGuiItem(guiMaterial("inventory.items.page-info.material", Material.BOOK),
                    applyPlaceholders(getRawMessage("gui.buttons.page-info", "&bPage &e{page} &bof &e{total}"), tokens),
                    configuredLore("gui.lore.page-info", tokens));
            gui.setItem(infoSlot, pageInfo);
        }

        int backSlot = guiConfig.getInt("inventory.items.admin-back-button.slot", -1);
        if (backSlot != -1) {
            ItemStack backBtn = buildGuiItem(guiMaterial("inventory.items.admin-back-button.material", Material.ARROW),
                    getRawMessage("gui.buttons.back-to-wardrobe", "&aBack to Your Wardrobe"),
                    Collections.emptyList());
            gui.setItem(backSlot, backBtn);
        }

        int setsPerPage = guiConfig.getInt("inventory.sets-per-page", 7);
        int startIndex = (page - 1) * setsPerPage;

        for (int slotIndex = 1; slotIndex <= setsPerPage; slotIndex++) {
            int globalIndex = startIndex + (slotIndex - 1);
            ArmorSet set = (globalIndex < armorSets.size() ? armorSets.get(globalIndex) : null);

            String prefix = "inventory.set-layout." + slotIndex + ".";
            int nameSlot = guiConfig.getInt(prefix + "nameplate", -1);
            int helmSlot = guiConfig.getInt(prefix + "helmet", -1);
            int chestSlot = guiConfig.getInt(prefix + "chestplate", -1);
            int legsSlot = guiConfig.getInt(prefix + "leggings", -1);
            int bootsSlot = guiConfig.getInt(prefix + "boots", -1);
            int actionSlot = guiConfig.getInt(prefix + "action", -1);

            if (nameSlot != -1) {
                ItemStack nameplate = new ItemStack(guiMaterial(set != null ? "inventory.items.nameplate.material" : "inventory.items.nameplate.empty-material", Material.NAME_TAG));
                ItemMeta nm = nameplate.getItemMeta();
                if (nm != null) {
                    nm.displayName(colorize(set != null ? "&f" + set.getName() : getRawMessage("gui.buttons.empty-slot", "&7Empty armour slot")));
                    Map<String, String> tokens = placeholders("name", set != null ? set.getName() : "", "slot", globalIndex + 1);
                    List<Component> lore = configuredLore(set != null ? "gui.lore.nameplate" : "gui.lore.empty-slot", tokens);
                    lore.addAll(buildToolLore(set));
                    if (!lore.isEmpty()) nm.lore(lore);
                    nameplate.setItemMeta(nm);
                }
                gui.setItem(nameSlot, nameplate);
            }

            Material placeholderColor = theme.emptySlotIcon != null ? theme.emptySlotIcon : (theme.primary != null ? theme.primary : SPECTRUM[globalIndex % SPECTRUM.length]);
            if (helmSlot != -1) gui.setItem(helmSlot, set != null ? defaultIfNull(set.getArmor()[3]) : emptyArmorPlaceholder(placeholderColor));
            if (chestSlot != -1) gui.setItem(chestSlot, set != null ? defaultIfNull(set.getArmor()[2]) : emptyArmorPlaceholder(placeholderColor));
            if (legsSlot != -1) gui.setItem(legsSlot, set != null ? defaultIfNull(set.getArmor()[1]) : emptyArmorPlaceholder(placeholderColor));
            if (bootsSlot != -1) gui.setItem(bootsSlot, set != null ? defaultIfNull(set.getArmor()[0]) : emptyArmorPlaceholder(placeholderColor));
            renderToolPreviewSlots(gui, slotIndex, set, true, null);

            if (actionSlot != -1) {
                ItemStack actionBtn;
                if (set != null) {
                    actionBtn = new ItemStack(guiMaterial("inventory.items.actions.inspect-material", Material.ENDER_EYE));
                    ItemMeta meta = actionBtn.getItemMeta();
                    if (meta != null) {
                        Map<String, String> tokens = placeholders("name", set.getName(), "slot", globalIndex + 1);
                        meta.displayName(colorize(applyPlaceholders(getRawMessage("gui.buttons.view-set", "&bView: &7{name}"), tokens)));
                        List<Component> lore = configuredLore("gui.lore.equip", tokens);
                        lore.addAll(buildToolLore(set));
                        if (!lore.isEmpty()) meta.lore(lore);
                        actionBtn.setItemMeta(meta);
                    }
                } else {
                    actionBtn = buildGuiItem(guiMaterial("inventory.items.actions.empty-material", Material.GRAY_STAINED_GLASS_PANE),
                            getRawMessage("gui.buttons.empty-action", "&7Empty Slot"),
                            configuredLore("gui.lore.empty-slot", placeholders("slot", globalIndex + 1)));
                }
                gui.setItem(actionSlot, actionBtn);
            }
        }

        admin.openInventory(gui);
        openInventories.put(adminUUID, gui);
        playSound(admin, "sounds.open");
    }

    private void openThemesGUI(Player player) {
        String titleRaw = getRawMessage("themes_gui.title", "&b⚡ Select Your Theme");
        Inventory gui = Bukkit.createInventory(player, 54, colorize(titleRaw));
        UUID uuid = player.getUniqueId();

        String backTextRaw = getRawMessage("themes_gui.back-button", "&a← Back to Wardrobe");
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) { backMeta.displayName(colorize(backTextRaw)); back.setItemMeta(backMeta); }
        gui.setItem(0, back);

        Material fillerMat = Material.LIGHT_GRAY_STAINED_GLASS_PANE;
        ItemStack filler = new ItemStack(fillerMat);
        ItemMeta fMeta = filler.getItemMeta();
        if (fMeta != null) { fMeta.displayName(Component.space()); filler.setItemMeta(fMeta); }
        for (int i = 1; i < gui.getSize(); i++) gui.setItem(i, filler);

        int[] themeSlots = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};
        int idx = 0;
        String selectedKey = getSelectedThemeKey(uuid);

        for (Theme t : themeRegistry.values()) {
            if (idx >= themeSlots.length) break;
            boolean unlocked = t.permission == null || t.permission.isEmpty() || player.hasPermission(t.permission);

            ItemStack icon = new ItemStack(unlocked ? (t.icon != null ? t.icon : Material.BOOK) : (t.lockedIcon != null ? t.lockedIcon : Material.BARRIER));
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                if (unlocked) {
                    meta.displayName(Component.text(t.name, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                    if (t.key.equalsIgnoreCase(selectedKey)) {
                        setGlint(meta, true);
                        meta.lore(Collections.singletonList(colorize(getRawMessage("themes_gui.theme-selected", "&b✔ Selected"))));
                    } else {
                        meta.lore(Collections.singletonList(colorize(getRawMessage("themes_gui.theme-apply", "&7Click to apply"))));
                    }
                } else {
                    meta.displayName(colorize(getRawMessage("themes_gui.locked-theme", "&cLocked Theme")));
                    List<Component> lore = new ArrayList<>();
                    lore.add(Component.text("Locked - Permission required", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                    if (t.permission != null && !t.permission.isEmpty()) {
                        lore.add(colorize(getRawMessage("themes_gui.locked-permission", "&7Permission: &f{permission}").replace("{permission}", t.permission)));
                    }
                    meta.lore(lore);
                }
                icon.setItemMeta(meta);
            }
            gui.setItem(themeSlots[idx], icon);
            idx++;
        }

        player.openInventory(gui);
        openThemeInventories.put(uuid, gui);

        BukkitRunnable existing = themeAnimationTasks.remove(uuid);
        if (existing != null) { try { existing.cancel(); } catch (Exception ignored) {} }
        startThemeGUIAnimation(player, gui, selectedKey);
    }

    private void startThemeGUIAnimation(Player player, Inventory gui, String initialSelectedKey) {
        UUID uuid = player.getUniqueId();
        boolean enableAnimations = config != null && config.getBoolean("themes-gui.enable-animations", true);
        if (!enableAnimations) return;

        List<String> rainbowList = config != null ? config.getStringList("themes-gui.border-rainbow-colors") : Collections.emptyList();
        String bg1 = config != null ? config.getString("themes-gui.background-color-1", "LIGHT_GRAY_STAINED_GLASS_PANE") : "LIGHT_GRAY_STAINED_GLASS_PANE";
        String bg2 = config != null ? config.getString("themes-gui.background-color-2", "WHITE_STAINED_GLASS_PANE") : "WHITE_STAINED_GLASS_PANE";
        int updateInterval = config != null ? Math.max(1, config.getInt("themes-gui.update-interval", 6)) : 6;

        final Material[] defaultRainbow = new Material[] { Material.RED_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE, Material.PURPLE_STAINED_GLASS_PANE, Material.PINK_STAINED_GLASS_PANE };
        final Material[] rainbow;
        if (rainbowList != null && !rainbowList.isEmpty()) {
            List<Material> mats = new ArrayList<>();
            for (String s : rainbowList) { Material m = Material.matchMaterial(s); if (m != null) mats.add(m); }
            rainbow = mats.isEmpty() ? defaultRainbow : mats.toArray(new Material[0]);
        } else { rainbow = defaultRainbow; }

        final Material shadeA = matOrDefault(bg1, Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        final Material shadeB = matOrDefault(bg2, Material.WHITE_STAINED_GLASS_PANE);

        BukkitRunnable task = new BukkitRunnable() {
            private final int[] borderSlots = {1,2,3,4,5,6,7,8,9,17,18,26,27,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53};
            private final int[] themeGridSlots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
            int rainbowIndex = 0; boolean breatheToggle = false; boolean glowToggle = false; String lastSelectedKey = initialSelectedKey;

            @Override
            public void run() {
                if (!player.isOnline()) { cancel(); themeAnimationTasks.remove(uuid); openThemeInventories.remove(uuid); return; }
                Inventory current = openThemeInventories.get(uuid);
                if (current == null) { cancel(); themeAnimationTasks.remove(uuid); return; }
                InventoryView view = player.getOpenInventory();
                if (view == null) { cancel(); themeAnimationTasks.remove(uuid); openThemeInventories.remove(uuid); return; }
                Inventory top = view.getTopInventory();
                if (top == null || !top.equals(current)) { cancel(); themeAnimationTasks.remove(uuid); openThemeInventories.remove(uuid); return; }

                Material chosenShade = breatheToggle ? shadeA : shadeB;
                ItemStack filler = new ItemStack(chosenShade);
                ItemMeta fm = filler.getItemMeta();
                if (fm != null) { fm.displayName(Component.space()); filler.setItemMeta(fm); }
                for (int i = 1; i < current.getSize(); i++) { if (!isInThemeGrid(i)) current.setItem(i, filler); }
                breatheToggle = !breatheToggle;

                Material borderMat = rainbow[rainbowIndex % rainbow.length];
                ItemStack borderPane = new ItemStack(borderMat);
                ItemMeta bpm = borderPane.getItemMeta();
                if (bpm != null) { bpm.displayName(Component.space()); borderPane.setItemMeta(bpm); }
                for (int s : borderSlots) { if (s != 0 && !isInThemeGrid(s)) current.setItem(s, borderPane); }
                rainbowIndex = (rainbowIndex + 1) % rainbow.length;

                String selectedKey = playerThemes.getOrDefault(uuid, lastSelectedKey);
                for (int s : themeGridSlots) {
                    ItemStack it = current.getItem(s);
                    if (it == null || !it.hasItemMeta()) continue;
                    ItemMeta meta = it.getItemMeta();
                    if (meta == null) continue;
                    String display = meta.hasDisplayName() ? PlainTextComponentSerializer.plainText().serialize(meta.displayName()) : "";
                    Theme tFound = findThemeByName(display);
                    if (tFound != null && tFound.key.equalsIgnoreCase(selectedKey)) {
                        setGlint(meta, glowToggle);
                        it.setItemMeta(meta);
                    }
                }
                glowToggle = !glowToggle; lastSelectedKey = selectedKey;
            }
        };
        task.runTaskTimer(this, 0L, Math.max(1, updateInterval));
        themeAnimationTasks.put(uuid, task);
    }

    private boolean isInThemeGrid(int slot) {
        int[] themeGridSlots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
        for (int s : themeGridSlots) if (s == slot) return true;
        return false;
    }

    private Theme findThemeByName(String displayName) {
        if (displayName == null) return null;
        for (Theme t : themeRegistry.values()) { if (t.name.equalsIgnoreCase(displayName)) return t; }
        return null;
    }

    private void renderToolPreviewSlots(Inventory gui, int layoutIndex, ArmorSet set, boolean canView, ItemStack locked) {
        if (!isToolStorageEnabled() || guiConfig == null) return;
        String prefix = "inventory.set-layout." + layoutIndex + ".";
        for (int i = 0; i < maxStoredTools; i++) {
            int guiSlot = guiConfig.getInt(prefix + "tool-" + (i + 1), -1);
            if (guiSlot < 0 || guiSlot >= gui.getSize()) continue;

            if (!canView) {
                gui.setItem(guiSlot, locked != null ? locked : new ItemStack(Material.BARRIER));
                continue;
            }

            ItemStack saved = set != null ? set.getTool(i) : null;
            gui.setItem(guiSlot, defaultToolPlaceholder(saved, i));
        }
    }

    private List<Component> buildToolLore(ArmorSet set) {
        if (!isToolStorageEnabled() || set == null || config == null) {
            return Collections.emptyList();
        }
        if (!config.getBoolean("tools.show-tools-in-lore", true)) {
            return Collections.emptyList();
        }

        List<Component> lore = new ArrayList<>();
        ItemStack[] tools = set.getTools();
        for (int i = 0; i < Math.min(maxStoredTools, tools.length); i++) {
            ItemStack item = tools[i];
            if (isEmpty(item)) continue;
            lore.add(colorize("&8Tool " + (i + 1) + ": &f" + getPlainItemName(item)));
        }
        return lore;
    }

    private ItemStack buildGuiItem(Material material, String displayName, List<Component> lore) {
        ItemStack item = new ItemStack(material != null ? material : Material.STONE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(colorize(displayName));
            if (lore != null && !lore.isEmpty()) meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private Material guiMaterial(String path, Material fallback) {
        if (guiConfig == null || path == null) return fallback;
        return matOrDefault(guiConfig.getString(path), fallback);
    }

    private Map<String, String> placeholders(Object... pairs) {
        Map<String, String> tokens = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            tokens.put(String.valueOf(pairs[i]), String.valueOf(pairs[i + 1]));
        }
        return tokens;
    }

    private String applyPlaceholders(String text, Map<String, String> tokens) {
        if (text == null || tokens == null || tokens.isEmpty()) return text;
        String result = text;
        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private List<Component> configuredLore(String path, Map<String, String> tokens) {
        List<Component> lore = new ArrayList<>();
        if (messages == null || path == null) return lore;
        for (String line : messages.getStringList(path)) {
            lore.add(colorize(applyPlaceholders(line, tokens)));
        }
        return lore;
    }

    private ItemStack emptyArmorPlaceholder(Material color) {
        Material finalMat = color != null ? color : guiMaterial("inventory.items.armor.empty-material", Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemStack placeholder = new ItemStack(finalMat);
        ItemMeta meta = placeholder.getItemMeta();
        if (meta != null) { meta.displayName(colorize(getRawMessage("gui.buttons.empty-armor-slot", "&cEmpty Slot"))); placeholder.setItemMeta(meta); }
        return placeholder;
    }

    private ItemStack defaultIfNull(ItemStack stack) {
        if (stack != null && stack.getType() != Material.AIR) return stack;
        ItemStack placeholder = new ItemStack(guiMaterial("inventory.items.armor.empty-preview-material", Material.LIGHT_GRAY_STAINED_GLASS_PANE));
        ItemMeta meta = placeholder.getItemMeta();
        if (meta != null) { meta.displayName(colorize(getRawMessage("gui.buttons.empty-placeholder", "&7(Empty)"))); placeholder.setItemMeta(meta); }
        return placeholder;
    }

    private ItemStack defaultToolPlaceholder(ItemStack stack, int toolIndex) {
        if (!isEmpty(stack)) return stack;
        ItemStack placeholder = new ItemStack(guiMaterial("inventory.items.tool.empty-material", emptyToolIcon != null ? emptyToolIcon : Material.IRON_PICKAXE));
        ItemMeta meta = placeholder.getItemMeta();
        if (meta != null) {
            meta.displayName(colorize(getRawMessage("gui.buttons.empty-tool-slot", "&8&o(Empty Tool {number})")
                    .replace("{number}", String.valueOf(toolIndex + 1))));
            placeholder.setItemMeta(meta);
        }
        return placeholder;
    }

    private void playSound(Player player, String configPath) {
        if (config == null || !config.getBoolean("sounds.enabled", true)) return;
        Sound sound = SOUND_MAP.get(config.getString(configPath, "BLOCK_CHEST_OPEN"));
        if (sound == null) {
            try { sound = (Sound) Sound.class.getMethod("valueOf", String.class).invoke(null, config.getString(configPath, "BLOCK_CHEST_OPEN")); } catch (Exception ignored) { sound = Sound.BLOCK_CHEST_OPEN; }
        }
        player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, (float) config.getDouble("sounds.volume", 0.8), (float) config.getDouble("sounds.pitch", 1.0));
    }

    private Particle particleFromConfig(String path, String fallback) {
        String configured = config != null ? config.getString(path, fallback) : fallback;
        Particle particle = particleByName(configured);
        if (particle != null) return particle;
        particle = particleByName(fallback);
        return particle != null ? particle : Particle.CLOUD;
    }

    private Particle particleByName(String name) {
        if (name == null || name.isBlank()) return null;
        String normalized = name.toUpperCase(Locale.ROOT);
        if (normalized.equals("SPELL_WITCH")) normalized = "WITCH";
        try {
            return Particle.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            if (normalized.equals("WITCH")) {
                try { return Particle.valueOf("SPELL_WITCH"); } catch (IllegalArgumentException ignoredAgain) { return null; }
            }
            return null;
        }
    }

    private void playSelectParticleEffect(Player player) {
        if (!animationsEnabled()) return;
        Particle particle = particleFromConfig("particles.select-effect", "END_ROD");
        spawnParticleSafely(player, particle, player.getLocation().add(0, 1.1, 0),
                clamp(config.getInt("particles.select-count", 10), 1, 40),
                0.25, 0.35, 0.25, 0.02);
    }

    private void playApplyParticleEffect(Player player) {
        if (!animationsEnabled()) return;
        Particle particle = particleFromConfig("particles.apply-effect", "SPELL_WITCH");
        Location center = player.getLocation().add(0, 0.9, 0);
        int points = clamp(config.getInt("particles.apply-ring-points", 14), 6, 28);
        double radius = clamp(config.getDouble("particles.apply-radius", 0.75), 0.2, 2.0);
        for (int i = 0; i < points; i++) {
            double angle = (Math.PI * 2.0 * i) / points;
            Location point = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            spawnParticleSafely(player, particle, point, 1, 0.02, 0.05, 0.02, 0.0);
        }
    }

    private void playEquipParticleEffect(Player player) {
        if (!animationsEnabled()) return;

        final Particle primary = particleFromConfig("particles.equip-effect", "END_ROD");
        final Particle secondary = particleFromConfig("particles.equip-secondary-effect", "CRIT");
        final int steps = clamp(config.getInt("particles.equip-steps", config.getInt("particles.count", 12)), 4, 32);
        final int perStep = clamp(config.getInt("particles.equip-particles-per-step", 2), 1, 8);
        final double radius = clamp(config.getDouble("particles.equip-radius", 0.65), 0.2, 1.5);
        final long interval = clamp(config.getInt("particles.equip-interval-ticks", 2), 1, 10);

        new BukkitRunnable() {
            int step = 0;

            @Override
            public void run() {
                if (!player.isOnline() || step >= steps) {
                    cancel();
                    return;
                }

                Location base = player.getLocation().add(0, 0.25 + (1.35 * step / Math.max(1, steps - 1)), 0);
                double angle = step * 0.85;
                Location first = base.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
                Location second = base.clone().add(Math.cos(angle + Math.PI) * radius, 0, Math.sin(angle + Math.PI) * radius);

                spawnParticleSafely(player, primary, first, perStep, 0.03, 0.03, 0.03, 0.0);
                spawnParticleSafely(player, primary, second, perStep, 0.03, 0.03, 0.03, 0.0);
                if (step % 3 == 0) {
                    spawnParticleSafely(player, secondary, base, 2, 0.2, 0.25, 0.2, 0.02);
                }
                step++;
            }
        }.runTaskTimer(this, 0L, interval);
    }

    private boolean animationsEnabled() {
        return config != null && config.getBoolean("settings.enable-animations", true);
    }

    private void spawnParticleSafely(Player player, Particle particle, Location location, int count,
                                     double offsetX, double offsetY, double offsetZ, double speed) {
        try {
            player.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, speed, null, true);
        } catch (IllegalArgumentException error) {
            player.spawnParticle(Particle.CLOUD, location, Math.max(1, Math.min(count, 4)), 0.15, 0.2, 0.15, 0.02, null, true);
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void setGlint(ItemMeta meta, boolean enabled) {
        try {
            ItemMeta.class.getMethod("setEnchantmentGlintOverride", Boolean.class).invoke(meta, enabled);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        UUID uuid = player.getUniqueId();

        Inventory top = event.getView().getTopInventory();
        Inventory ourTop = openInventories.get(uuid);
        Inventory themeTop = openThemeInventories.get(uuid);

        if ((ourTop != null && top.equals(ourTop)) || (themeTop != null && top.equals(themeTop))) {
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < top.getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        UUID uuid = player.getUniqueId();

        InventoryView view = event.getView();
        Inventory top = view.getTopInventory();
        Inventory ourTop = openInventories.get(uuid);
        Inventory themeTop = openThemeInventories.get(uuid);

        boolean isWardrobeGui = ourTop != null && top.equals(ourTop);
        boolean isThemeGui = themeTop != null && top.equals(themeTop);

        if (!isWardrobeGui && !isThemeGui) return;

        if (event.getClick() == ClickType.NUMBER_KEY ||
            event.getClick() == ClickType.SWAP_OFFHAND ||
            event.getClick() == ClickType.DOUBLE_CLICK ||
            event.getClick() == ClickType.DROP ||
            event.getClick() == ClickType.CONTROL_DROP) {
            event.setCancelled(true);
            return;
        }

        if (event.isShiftClick()) {
            event.setCancelled(true);
            return;
        }

        if (isThemeGui) {
            event.setCancelled(true);
            handleThemeClick(event, player, themeTop);
            return;
        }

        if (adminViewTargets.containsKey(uuid)) {
            event.setCancelled(true);
            if (event.getClickedInventory() != null && event.getClickedInventory().equals(top)) {
                int slot = event.getSlot();
                if (slot == guiConfig.getInt("inventory.items.admin-back-button.slot", -1)) {
                    player.closeInventory();
                    adminViewTargets.remove(uuid);
                    Bukkit.getScheduler().runTaskLater(this, () -> openWardrobeGUI(player, currentPages.getOrDefault(uuid, 1)), 1L);
                }
            }
            return;
        }

        if (event.getClickedInventory() != null && event.getClickedInventory().equals(top)) {
            event.setCancelled(true);
        } else {
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= top.getSize()) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        long now = System.currentTimeMillis();
        Long lastAction = actionDebounce.get(uuid);
        if (lastAction != null && (now - lastAction) < 250L) {
            return;
        }
        actionDebounce.put(uuid, now);

        int slot = event.getSlot();
        int currentPage = currentPages.getOrDefault(uuid, 1);
        int maxPages = getMaxPages();
        int setsPerPage = guiConfig.getInt("inventory.sets-per-page", 7);
        List<ArmorSet> armorSets = playerArmorSets.computeIfAbsent(uuid, k -> new ArrayList<>());

        if (slot == guiConfig.getInt("inventory.items.themes-button.slot", -1)) {
            openThemesGUI(player);
            return;
        }

        if (slot == guiConfig.getInt("inventory.items.previous-page.slot", -1)) {
            if (currentPage > 1) openWardrobeGUI(player, currentPage - 1);
            return;
        }

        if (slot == guiConfig.getInt("inventory.items.next-page.slot", -1)) {
            if (currentPage < maxPages) openWardrobeGUI(player, currentPage + 1);
            return;
        }

        int clickedSetLocalIndex = -1;
        boolean isNameplate = false;
        boolean isAction = false;

        for (int i = 1; i <= setsPerPage; i++) {
            if (slot == guiConfig.getInt("inventory.set-layout." + i + ".nameplate", -1)) {
                clickedSetLocalIndex = i - 1;
                isNameplate = true;
                break;
            }
            if (slot == guiConfig.getInt("inventory.set-layout." + i + ".action", -1)) {
                clickedSetLocalIndex = i - 1;
                isAction = true;
                break;
            }
        }

        if (clickedSetLocalIndex != -1) {
            int globalSetIndex = (currentPage - 1) * setsPerPage + clickedSetLocalIndex;

            if (!hasSlotPermission(player, globalSetIndex)) {
                player.sendMessage(colorize(getRawMessage("errors.no-slot-permission", "&c✖ &7You don't have permission to access this slot!")));
                return;
            }

            if (isNameplate && event.isRightClick()) {
                if (globalSetIndex < armorSets.size() && armorSets.get(globalSetIndex) != null) {
                    startRenamingProcess(player, globalSetIndex);
                } else {
                    player.sendMessage(colorize("&c✖ &7No set in that slot to rename."));
                }
            } else if (isAction) {
                if (globalSetIndex < armorSets.size() && armorSets.get(globalSetIndex) != null) {
                    equipArmorSet(player, globalSetIndex);
                } else {
                    saveArmorSet(player, globalSetIndex);
                }
            }
        }
    }

    private void handleThemeClick(InventoryClickEvent event, Player player, Inventory themeInventory) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        int slot = event.getSlot();
        UUID uuid = player.getUniqueId();

        if (slot == 0) {
            player.closeInventory();
            Bukkit.getScheduler().runTaskLater(this, () -> openWardrobeGUI(player, currentPages.getOrDefault(uuid, 1)), 1L);
            return;
        }

        if (isInThemeGrid(slot)) {
            ItemMeta meta = clicked.getItemMeta();
            if (meta == null || !meta.hasDisplayName()) return;
            String display = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
            Theme clickedTheme = findThemeByName(display);
            if (clickedTheme == null) return;

            boolean unlocked = clickedTheme.permission == null || clickedTheme.permission.isEmpty() || player.hasPermission(clickedTheme.permission);
            if (!unlocked) {
                player.sendMessage(colorize("&c✖ &7You don't have permission to use that theme."));
                playSound(player, "sounds.save");
                return;
            }

            ItemStack original = clicked.clone();
            ItemStack flash = new ItemStack(Material.GOLD_BLOCK);
            ItemMeta fm = flash.getItemMeta();
            if (fm != null) { fm.displayName(Component.text("Applying...", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE)); flash.setItemMeta(fm); }
            themeInventory.setItem(slot, flash);

            playSelectParticleEffect(player);

            Bukkit.getScheduler().runTaskLater(this, () -> {
                themeInventory.setItem(slot, original);
                applyThemeToPlayer(player, clickedTheme);
            }, 6L);
        }
    }

    private void applyThemeToPlayer(Player player, Theme theme) {
        UUID uuid = player.getUniqueId();
        playerThemes.put(uuid, theme.key);
        savePlayerTheme(uuid);
        player.sendMessage(colorize("&a✔ &7Theme set to &f" + theme.name));
        playSound(player, "sounds.equip");
        playApplyParticleEffect(player);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        openInventories.remove(uuid);
        adminViewTargets.remove(uuid);
        openThemeInventories.remove(uuid);
        actionDebounce.remove(uuid);
        BukkitRunnable t = themeAnimationTasks.remove(uuid);
        if (t != null) try { t.cancel(); } catch (Exception ignored) {}
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (playerArmorSets.containsKey(uuid)) savePlayerData(uuid);
        renamingPlayers.remove(uuid);
        renamingSlots.remove(uuid);
        cooldowns.remove(uuid);
        actionDebounce.remove(uuid);
        currentPages.remove(uuid);
        openInventories.remove(uuid);
        adminViewTargets.remove(uuid);
        openThemeInventories.remove(uuid);
        BukkitRunnable t = themeAnimationTasks.remove(uuid);
        if (t != null) try { t.cancel(); } catch (Exception ignored) {}
    }

    private void startRenamingProcess(Player player, int setIndex) {
        List<ArmorSet> armorSets = playerArmorSets.get(player.getUniqueId());
        if (armorSets == null || setIndex < 0 || setIndex >= armorSets.size()) return;
        ArmorSet set = armorSets.get(setIndex);
        if (set == null) return;

        renamingPlayers.put(player.getUniqueId(), set.getName());
        renamingSlots.put(player.getUniqueId(), setIndex);

        player.closeInventory();
        player.sendMessage(colorize(getRawMessage("messages.rename-prompt", "&e✎ &7Type the new name in chat:")));
        player.sendMessage(colorize(getRawMessage("messages.rename-cancel", "&7Type &c'cancel' &7to cancel renaming.")));
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        if (!renamingPlayers.containsKey(id)) return;

        event.setCancelled(true);
        String message = event.getMessage();

        Bukkit.getScheduler().runTask(this, () -> handleRenameChat(player, message));
    }

    private void handleRenameChat(Player player, String message) {
        UUID id = player.getUniqueId();
        if (!renamingPlayers.containsKey(id)) return;

        if (message.equalsIgnoreCase("cancel")) {
            player.sendMessage(colorize(getRawMessage("messages.rename-cancelled", "&c✖ &7Renaming cancelled")));
            renamingPlayers.remove(id);
            renamingSlots.remove(id);
            openWardrobeGUI(player, currentPages.getOrDefault(id, 1));
            return;
        }

        int maxLength = Math.max(1, config != null ? config.getInt("settings.max-name-length", 16) : 16);
        int minLength = Math.max(1, config != null ? config.getInt("settings.min-name-length", 3) : 3);
        if (message.length() > maxLength || message.length() < minLength) {
            String path = message.length() > maxLength ? "errors.name-too-long" : "errors.name-too-short";
            String fallback = message.length() > maxLength ? "&c✖ &7Name too long!" : "&c✖ &7Name too short!";
            player.sendMessage(colorize(getRawMessage(path, fallback)
                    .replace("{max}", String.valueOf(maxLength))
                    .replace("{min}", String.valueOf(minLength))));
            return;
        }

        Integer slot = renamingSlots.get(id);
        List<ArmorSet> armorSets = playerArmorSets.get(id);
        if (slot != null && armorSets != null && slot >= 0 && slot < armorSets.size() && armorSets.get(slot) != null) {
            armorSets.get(slot).setName(message);
            savePlayerData(id);
            player.sendMessage(colorize(getRawMessage("messages.renamed", "&a✎ &7Renamed to &b{new}&7!").replace("{new}", message)));
            playSound(player, "sounds.save");
        }

        renamingPlayers.remove(id);
        renamingSlots.remove(id);
        openWardrobeGUI(player, currentPages.getOrDefault(id, 1));
    }

    private ToolCapture captureToolsFromHands(Player player) {
        if (!isToolStorageEnabled()) {
            return ToolCapture.empty();
        }

        ItemStack[] tools = new ItemStack[2];
        boolean capturedMainHand = false;
        boolean capturedOffHand = false;

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (maxStoredTools >= 1 && canStoreTool(mainHand)) {
            tools[0] = mainHand.clone();
            capturedMainHand = true;
        }

        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (maxStoredTools >= 2 && canStoreTool(offHand)) {
            tools[1] = offHand.clone();
            capturedOffHand = true;
        }

        return new ToolCapture(tools, capturedMainHand, capturedOffHand);
    }

    private void clearCapturedToolHands(Player player, ToolCapture capture) {
        if (capture == null) return;
        if (capture.capturedMainHand) {
            player.getInventory().setItemInMainHand(null);
        }
        if (capture.capturedOffHand) {
            player.getInventory().setItemInOffHand(null);
        }
    }

    private List<String> getBlockedToolHands(Player player, ArmorSet set) {
        if (!isToolStorageEnabled() || set == null) {
            return Collections.emptyList();
        }

        List<String> blocked = new ArrayList<>();
        if (!isEmpty(set.getTool(0))) {
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            if (!isEmpty(mainHand) && !canStoreTool(mainHand)) {
                blocked.add("main hand");
            }
        }
        if (maxStoredTools >= 2 && !isEmpty(set.getTool(1))) {
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (!isEmpty(offHand) && !canStoreTool(offHand)) {
                blocked.add("off hand");
            }
        }
        return blocked;
    }

    private void applyStoredTools(Player player, ArmorSet set, ToolCapture replacedHands) {
        if (!isToolStorageEnabled() || set == null) {
            return;
        }
        if (maxStoredTools >= 1 && (!isEmpty(set.getTool(0)) || (replacedHands != null && replacedHands.capturedMainHand))) {
            player.getInventory().setItemInMainHand(set.getTool(0));
        }
        if (maxStoredTools >= 2 && (!isEmpty(set.getTool(1)) || (replacedHands != null && replacedHands.capturedOffHand))) {
            player.getInventory().setItemInOffHand(set.getTool(1));
        }
    }

    private void sendBlockedToolMessage(Player player, List<String> blockedSlots) {
        String joined = String.join(", ", blockedSlots);
        player.sendMessage(colorize(getRawMessage("errors.blocked-tool-slot", "&c! &7Move unsupported items out of your &b{slots}&7 first.")
                .replace("{slots}", joined)));
    }

    private void saveArmorSet(Player player, int globalIndex) {
        List<ArmorSet> armorSets = playerArmorSets.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        int maxSets = config != null ? config.getInt("settings.max-armor-sets", 56) : 56;

        int existingCount = 0;
        for (ArmorSet s : armorSets) if (s != null) existingCount++;

        if (existingCount >= maxSets) {
            player.sendMessage(colorize(getRawMessage("errors.max-sets", "&c! &7Maximum armor sets reached (&b{max}&7)!").replace("{max}", String.valueOf(maxSets))));
            return;
        }

        ItemStack[] currentArmor = copyArmor(player.getInventory().getArmorContents());
        ToolCapture currentTools = captureToolsFromHands(player);
        boolean toolMode = isToolStorageEnabled();
        boolean heldHandItems = toolMode && hasAnyItem(new ItemStack[] {
                player.getInventory().getItemInMainHand(),
                player.getInventory().getItemInOffHand()
        });
        boolean savedTools = toolMode && hasAnyItem(currentTools.tools);
        boolean hasArmor = hasAnyItem(currentArmor);
        if (!hasArmor) {
            player.sendMessage(colorize(getRawMessage("errors.no-armor", "&c! &7You're not wearing any armor!")));
            return;
        }

        player.getInventory().setArmorContents(new ItemStack[4]);
        clearCapturedToolHands(player, currentTools);

        while (armorSets.size() <= globalIndex) armorSets.add(null);

        ArmorSet newSet = new ArmorSet("Set #" + (globalIndex + 1), currentArmor, currentTools.tools);
        armorSets.set(globalIndex, newSet);
        savePlayerData(player.getUniqueId());

        player.sendMessage(colorize(getRawMessage(savedTools ? "messages.saved-tools" : "messages.saved",
                savedTools ? "&a+ &7Armor and tools saved as &f{name}&7!" : "&a+ &7Armor saved as &f{name}&7!")
                .replace("{name}", newSet.getName())));
        if (toolMode && heldHandItems && !savedTools) {
            player.sendMessage(colorize(getRawMessage("messages.no-tools-captured",
                    "&e! &7No eligible tools were saved. Hold configured tools in your main hand/off hand.")));
        }
        playSound(player, "sounds.save");
        openWardrobeGUI(player, currentPages.getOrDefault(player.getUniqueId(), 1));
    }

    private void equipArmorSet(Player player, int globalIndex) {
        List<ArmorSet> armorSets = playerArmorSets.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        if (globalIndex >= armorSets.size() || armorSets.get(globalIndex) == null) return;
        ArmorSet newSet = armorSets.get(globalIndex);

        List<String> blockedToolHands = getBlockedToolHands(player, newSet);
        if (!blockedToolHands.isEmpty()) {
            sendBlockedToolMessage(player, blockedToolHands);
            return;
        }

        ItemStack[] currentArmor = copyArmor(player.getInventory().getArmorContents());
        ToolCapture currentTools = captureToolsFromHands(player);
        boolean hasCurrentWardrobeItems = hasAnyItem(currentArmor) || (isToolStorageEnabled() && hasAnyItem(currentTools.tools));

        if (hasCurrentWardrobeItems) armorSets.set(globalIndex, new ArmorSet(newSet.getName(), currentArmor, currentTools.tools));
        else armorSets.set(globalIndex, null);

        player.getInventory().setArmorContents(copyArmor(newSet.getArmor()));
        clearCapturedToolHands(player, currentTools);
        applyStoredTools(player, newSet, currentTools);
        savePlayerData(player.getUniqueId());

        player.sendMessage(colorize(getRawMessage("messages.loaded", "&a* &7Equipped &b{name}&7!").replace("{name}", newSet.getName())));
        playSound(player, "sounds.equip");
        playEquipParticleEffect(player);
        openWardrobeGUI(player, currentPages.getOrDefault(player.getUniqueId(), 1));
    }

    public List<ArmorSet> getArmorSetsFor(UUID uuid) {
        if (uuid == null) return Collections.emptyList();
        if (!playerArmorSets.containsKey(uuid) && dataManager != null) {
            playerArmorSets.put(uuid, dataManager.loadPlayerArmorSets(uuid));
        }
        return playerArmorSets.getOrDefault(uuid, Collections.emptyList());
    }

    public int countUsedArmorSets(List<ArmorSet> sets) {
        int count = 0;
        if (sets == null) return count;
        for (ArmorSet set : sets) {
            if (set != null) count++;
        }
        return count;
    }

    public int getConfiguredMaxArmorSets() {
        return config != null ? config.getInt("settings.max-armor-sets", 56) : 56;
    }

    public int getConfiguredMaxPages() {
        return getMaxPages();
    }

    public boolean isToolStorageEnabled() {
        return toolStorageEnabled && maxStoredTools > 0;
    }

    public int getConfiguredMaxStoredTools() {
        return isToolStorageEnabled() ? maxStoredTools : 0;
    }

    public int getCurrentPage(UUID uuid) {
        return currentPages.getOrDefault(uuid, 1);
    }

    public int getThemeCount() {
        return themeRegistry.size();
    }

    public String getSelectedThemeKey(Player player) {
        return getSelectedTheme(player).key;
    }

    public String getSelectedThemeKey(UUID uuid) {
        if (uuid == null) return "classic";
        if (!playerThemes.containsKey(uuid) && dataManager != null) {
            playerThemes.put(uuid, dataManager.loadPlayerTheme(uuid));
        }
        String key = playerThemes.getOrDefault(uuid, "classic");
        return themeRegistry.containsKey(key.toLowerCase(Locale.ROOT)) ? key : "classic";
    }

    public String getSelectedThemeName(Player player) {
        return getSelectedTheme(player).name;
    }

    public String getThemeName(String key) {
        Theme theme = key != null ? themeRegistry.get(key.toLowerCase(Locale.ROOT)) : null;
        if (theme != null) return theme.name;
        Theme fallback = themeRegistry.get("classic");
        return fallback != null ? fallback.name : "Classic";
    }

    public boolean isRenaming(UUID uuid) {
        return uuid != null && renamingPlayers.containsKey(uuid);
    }

    public int getCooldownRemainingSeconds(UUID uuid) {
        if (config == null || uuid == null || !cooldowns.containsKey(uuid)) return 0;
        long cooldownSeconds = config.getInt("settings.cooldown-seconds", 0);
        long elapsedSeconds = (System.currentTimeMillis() - cooldowns.get(uuid)) / 1000;
        return (int) Math.max(0, cooldownSeconds - elapsedSeconds);
    }

    public boolean hasWardrobeSlotAccess(Player player, int oneBasedSlot) {
        return player != null && hasSlotPermission(player, oneBasedSlot - 1);
    }

    public String getStorageType() {
        return dataManager instanceof MySQLDataManager ? "mysql" : "file";
    }

    private Component colorize(String message) {
        if (message == null) return Component.empty();
        return LegacyComponentSerializer.legacyAmpersand().deserialize(message).decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private Material matOrDefault(String name, Material def) {
        if (name == null || name.isEmpty()) return def;
        Material m = Material.matchMaterial(name);
        return m != null ? m : def;
    }

    private ItemStack[] copyArmor(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[4];
        if (source == null) return copy;
        for (int i = 0; i < copy.length && i < source.length; i++) {
            copy[i] = isEmpty(source[i]) ? null : source[i].clone();
        }
        return copy;
    }

    private boolean hasAnyItem(ItemStack[] items) {
        if (items == null) return false;
        for (ItemStack item : items) {
            if (!isEmpty(item)) return true;
        }
        return false;
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }

    private boolean canStoreTool(ItemStack item) {
        if (isEmpty(item) || !isToolStorageEnabled()) return false;
        if (allowsCustomTool(item)) return true;

        String material = item.getType().name().toUpperCase(Locale.ROOT);
        for (String rawPattern : allowedToolMaterials) {
            if (rawPattern == null || rawPattern.isBlank()) continue;
            String pattern = rawPattern.trim().toUpperCase(Locale.ROOT);
            if (pattern.equals("*") || pattern.equals("ANY")) return true;
            if (pattern.indexOf('*') >= 0 && wildcardMatches(pattern, material)) return true;
            if (material.equals(pattern)) return true;
        }
        return false;
    }

    private boolean allowsCustomTool(ItemStack item) {
        try {
            if (!allowedCustomTools.isEmpty()) {
                ItemProvider provider = HookManager.getProviderForItem(item);
                if (provider != null) {
                    String customId = provider.getCustomId(item);
                    if (customId != null && !customId.isBlank()) {
                        String idOnly = customId.toUpperCase(Locale.ROOT);
                        String withProvider = (provider.getPluginName() + ":" + customId).toUpperCase(Locale.ROOT);
                        for (String rawPattern : allowedCustomTools) {
                            if (matchesConfiguredPattern(rawPattern, idOnly, withProvider)) return true;
                        }
                    }
                }
            }
            return allowsPersistentDataTool(item);
        } catch (Exception | LinkageError ignored) {
            return false;
        }
    }

    private boolean allowsPersistentDataTool(ItemStack item) {
        if (allowedPersistentDataTools.isEmpty()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.getKeys().isEmpty()) return false;

        for (NamespacedKey key : pdc.getKeys()) {
            String keyId = (key.getNamespace() + ":" + key.getKey()).toUpperCase(Locale.ROOT);
            String value = persistentDataValue(pdc, key);
            String withValue = value.isEmpty() ? keyId : (keyId + "=" + value.toUpperCase(Locale.ROOT));
            for (String rawPattern : allowedPersistentDataTools) {
                if (matchesConfiguredPattern(rawPattern, keyId, withValue)) return true;
            }
        }
        return false;
    }

    private String persistentDataValue(PersistentDataContainer pdc, NamespacedKey key) {
        String value = pdc.get(key, PersistentDataType.STRING);
        if (value != null) return value;
        Integer integerValue = pdc.get(key, PersistentDataType.INTEGER);
        if (integerValue != null) return String.valueOf(integerValue);
        Long longValue = pdc.get(key, PersistentDataType.LONG);
        if (longValue != null) return String.valueOf(longValue);
        Double doubleValue = pdc.get(key, PersistentDataType.DOUBLE);
        if (doubleValue != null) return String.valueOf(doubleValue);
        Byte byteValue = pdc.get(key, PersistentDataType.BYTE);
        return byteValue != null ? String.valueOf(byteValue) : "";
    }

    private boolean matchesConfiguredPattern(String rawPattern, String... values) {
        if (rawPattern == null || rawPattern.isBlank()) return false;
        String pattern = rawPattern.trim().toUpperCase(Locale.ROOT);
        if (pattern.equals("*") || pattern.equals("ANY")) return true;
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            if (pattern.indexOf('*') >= 0) {
                if (wildcardMatches(pattern, value)) return true;
            } else if (pattern.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean wildcardMatches(String pattern, String value) {
        String[] parts = pattern.split("\\*", -1);
        int index = 0;
        boolean first = true;
        for (String part : parts) {
            if (part.isEmpty()) {
                first = false;
                continue;
            }
            int found = value.indexOf(part, index);
            if (found < 0 || (first && !pattern.startsWith("*") && found != 0)) return false;
            index = found + part.length();
            first = false;
        }
        return pattern.endsWith("*") || index == value.length();
    }

    private String getPlainItemName(ItemStack item) {
        if (isEmpty(item)) return "";
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String displayName = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
            if (!displayName.isBlank()) return displayName;
        }
        return humanizeKey(item.getType().name().toLowerCase(Locale.ROOT));
    }

    private String humanizeKey(String key) {
        if (key == null || key.isBlank()) return "";
        String[] parts = key.replace('-', '_').split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) builder.append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }

    public static class ArmorSet {
        private String name;
        private final ItemStack[] armor;
        private final ItemStack[] tools;
        public ArmorSet(String name, ItemStack[] armor) {
            this(name, armor, null);
        }
        public ArmorSet(String name, ItemStack[] armor, ItemStack[] tools) {
            this.name = name != null ? name : "Unnamed";
            this.armor = new ItemStack[4];
            if (armor != null) {
                for (int i = 0; i < Math.min(armor.length, this.armor.length); i++) {
                    this.armor[i] = armor[i] == null || armor[i].getType() == Material.AIR ? null : armor[i].clone();
                }
            }
            this.tools = new ItemStack[2];
            if (tools != null) {
                for (int i = 0; i < Math.min(tools.length, this.tools.length); i++) {
                    this.tools[i] = tools[i] == null || tools[i].getType() == Material.AIR ? null : tools[i].clone();
                }
            }
        }
        public String getName() { return name; }
        public void setName(String name) { this.name = name != null ? name : this.name; }
        public ItemStack[] getArmor() { return armor; }
        public ItemStack[] getTools() { return tools; }
        public ItemStack getTool(int index) {
            if (index < 0 || index >= tools.length) return null;
            ItemStack item = tools[index];
            return item == null ? null : item.clone();
        }
    }

    private static class ToolCapture {
        final ItemStack[] tools;
        final boolean capturedMainHand;
        final boolean capturedOffHand;

        ToolCapture(ItemStack[] tools, boolean capturedMainHand, boolean capturedOffHand) {
            this.tools = tools != null ? tools : new ItemStack[2];
            this.capturedMainHand = capturedMainHand;
            this.capturedOffHand = capturedOffHand;
        }

        static ToolCapture empty() {
            return new ToolCapture(new ItemStack[2], false, false);
        }
    }

    private static class Theme {
        String key, name, permission;
        int slot;
        Material icon, background, primary, secondary, emptySlotIcon, lockedIcon;
    }
}
