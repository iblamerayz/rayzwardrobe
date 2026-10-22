package org.rayz.rayzWardrobe.data;

import org.bukkit.inventory.ItemStack;
import org.rayz.rayzWardrobe.MySQLManager;
import org.rayz.rayzWardrobe.RayzWardrobe.ArmorSet;
import org.rayz.rayzWardrobe.util.ItemSerializer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * MySQL implementation of DataManager.
 */
public class MySQLDataManager implements DataManager {
    private final MySQLManager mySQLManager;

    public MySQLDataManager(MySQLManager mySQLManager) {
        this.mySQLManager = mySQLManager;
    }

    @Override
    public List<ArmorSet> loadPlayerArmorSets(UUID playerUUID) {
        List<ArmorSet> armorSets = new ArrayList<>();
        if (playerUUID == null) return armorSets;

        String query = "SELECT set_index, name, helmet, chestplate, leggings, boots "
                + "FROM rayzwardrobe_armor_sets WHERE player_uuid = ? ORDER BY set_index";

        try {
            Connection conn = mySQLManager.getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, playerUUID.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        int setIndex = rs.getInt("set_index");
                        ItemStack[] armor = new ItemStack[] {
                                ItemSerializer.itemStackFromBase64(rs.getString("boots")),
                                ItemSerializer.itemStackFromBase64(rs.getString("leggings")),
                                ItemSerializer.itemStackFromBase64(rs.getString("chestplate")),
                                ItemSerializer.itemStackFromBase64(rs.getString("helmet"))
                        };

                        while (armorSets.size() <= setIndex) {
                            armorSets.add(null);
                        }
                        armorSets.set(setIndex, new ArmorSet(rs.getString("name"), armor));
                    }
                }
            }

            String toolsQuery = "SELECT set_index, tool_index, item "
                    + "FROM rayzwardrobe_tools WHERE player_uuid = ? ORDER BY set_index, tool_index";
            try (PreparedStatement stmt = conn.prepareStatement(toolsQuery)) {
                stmt.setString(1, playerUUID.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        int setIndex = rs.getInt("set_index");
                        int toolIndex = rs.getInt("tool_index");
                        if (toolIndex < 0 || toolIndex > 1) continue;
                        while (armorSets.size() <= setIndex) {
                            armorSets.add(null);
                        }
                        ArmorSet set = armorSets.get(setIndex);
                        if (set == null) {
                            set = new ArmorSet("Set #" + (setIndex + 1), new ItemStack[4]);
                            armorSets.set(setIndex, set);
                        }
                        ItemStack[] tools = set.getTools();
                        tools[toolIndex] = ItemSerializer.itemStackFromBase64(rs.getString("item"));
                        armorSets.set(setIndex, new ArmorSet(set.getName(), set.getArmor(), tools));
                    }
                }
            }
        } catch (SQLException e) {
            mySQLManager.plugin.getLogger().log(Level.SEVERE, "Error loading armor sets from MySQL for player " + playerUUID + ".", e);
        }

        return armorSets;
    }

    @Override
    public void savePlayerArmorSets(UUID playerUUID, List<ArmorSet> armorSets) {
        if (playerUUID == null) return;
        if (armorSets == null) armorSets = new ArrayList<>();
        String deleteQuery = "DELETE FROM rayzwardrobe_armor_sets WHERE player_uuid = ?";
        String deleteToolsQuery = "DELETE FROM rayzwardrobe_tools WHERE player_uuid = ?";
        String insertQuery = "INSERT INTO rayzwardrobe_armor_sets "
                + "(player_uuid, set_index, name, helmet, chestplate, leggings, boots) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        String insertToolsQuery = "INSERT INTO rayzwardrobe_tools "
                + "(player_uuid, set_index, tool_index, item) VALUES (?, ?, ?, ?)";

        try {
            Connection conn = mySQLManager.getConnection();
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement deleteStmt = conn.prepareStatement(deleteQuery);
                 PreparedStatement deleteToolsStmt = conn.prepareStatement(deleteToolsQuery);
                 PreparedStatement insertStmt = conn.prepareStatement(insertQuery);
                 PreparedStatement insertToolsStmt = conn.prepareStatement(insertToolsQuery)) {
                deleteStmt.setString(1, playerUUID.toString());
                deleteStmt.executeUpdate();
                deleteToolsStmt.setString(1, playerUUID.toString());
                deleteToolsStmt.executeUpdate();

                for (int i = 0; i < armorSets.size(); i++) {
                    ArmorSet set = armorSets.get(i);
                    if (set == null) continue;
                    ItemStack[] armor = set.getArmor();

                    insertStmt.setString(1, playerUUID.toString());
                    insertStmt.setInt(2, i);
                    insertStmt.setString(3, set.getName());
                    insertStmt.setString(4, ItemSerializer.itemStackToBase64(armor.length > 3 ? armor[3] : null));
                    insertStmt.setString(5, ItemSerializer.itemStackToBase64(armor.length > 2 ? armor[2] : null));
                    insertStmt.setString(6, ItemSerializer.itemStackToBase64(armor.length > 1 ? armor[1] : null));
                    insertStmt.setString(7, ItemSerializer.itemStackToBase64(armor.length > 0 ? armor[0] : null));
                    insertStmt.addBatch();

                    ItemStack[] tools = set.getTools();
                    for (int j = 0; j < Math.min(2, tools.length); j++) {
                        if (tools[j] == null) continue;
                        insertToolsStmt.setString(1, playerUUID.toString());
                        insertToolsStmt.setInt(2, i);
                        insertToolsStmt.setInt(3, j);
                        insertToolsStmt.setString(4, ItemSerializer.itemStackToBase64(tools[j]));
                        insertToolsStmt.addBatch();
                    }
                }

                insertStmt.executeBatch();
                insertToolsStmt.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(previousAutoCommit);
                } catch (SQLException autoCommitError) {
                    mySQLManager.plugin.getLogger().log(Level.WARNING, "Could not restore MySQL auto-commit state.", autoCommitError);
                }
            }
        } catch (SQLException e) {
            mySQLManager.plugin.getLogger().log(Level.SEVERE, "Error saving armor sets to MySQL for player " + playerUUID + ".", e);
        }
    }

    @Override
    public String loadPlayerTheme(UUID playerUUID) {
        if (playerUUID == null) return "classic";
        String query = "SELECT theme_key FROM rayzwardrobe_player_themes WHERE player_uuid = ?";

        try {
            Connection conn = mySQLManager.getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, playerUUID.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("theme_key");
                    }
                }
            }
        } catch (SQLException e) {
            mySQLManager.plugin.getLogger().log(Level.SEVERE, "Error loading player theme from MySQL for player " + playerUUID + ".", e);
        }

        return "classic";
    }

    @Override
    public void savePlayerTheme(UUID playerUUID, String themeKey) {
        if (playerUUID == null) return;
        String query = "INSERT INTO rayzwardrobe_player_themes (player_uuid, theme_key) "
                + "VALUES (?, ?) ON DUPLICATE KEY UPDATE theme_key = ?";

        try {
            Connection conn = mySQLManager.getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                String safeThemeKey = themeKey == null || themeKey.isBlank() ? "classic" : themeKey;
                stmt.setString(1, playerUUID.toString());
                stmt.setString(2, safeThemeKey);
                stmt.setString(3, safeThemeKey);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            mySQLManager.plugin.getLogger().log(Level.SEVERE, "Error saving player theme to MySQL for player " + playerUUID + ".", e);
        }
    }

    @Override
    public boolean isReady() {
        try {
            return mySQLManager.getConnection() != null;
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void close() {
        mySQLManager.disconnect();
    }
}
