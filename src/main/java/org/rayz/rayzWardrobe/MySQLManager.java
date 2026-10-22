package org.rayz.rayzWardrobe;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Level;

/**
 * Handles MySQL connection lifecycle and schema creation.
 */
public class MySQLManager {
    public final RayzWardrobe plugin;
    private Connection connection;
    private String jdbcUrl;
    private Properties properties;

    public MySQLManager(RayzWardrobe plugin) {
        this.plugin = plugin;
    }

    public boolean connect() {
        FileConfiguration config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "config.yml"));
        String host = config.getString("mysql.host", "localhost");
        int port = config.getInt("mysql.port", 3306);
        String database = config.getString("mysql.database", "minecraft");
        String username = config.getString("mysql.username", "root");
        String password = config.getString("mysql.password", "");
        boolean useSSL = config.getBoolean("mysql.useSSL", false);
        int timeout = Math.max(1, config.getInt("mysql.connection-timeout-seconds", 10));

        jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSSL
                + "&autoReconnect=false"
                + "&useUnicode=true"
                + "&characterEncoding=utf8"
                + "&serverTimezone=UTC"
                + "&connectTimeout=" + (timeout * 1000)
                + "&socketTimeout=" + (timeout * 1000);

        properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);

        try {
            connection = DriverManager.getConnection(jdbcUrl, properties);
            createTables(connection);
            plugin.getLogger().info("Successfully connected to MySQL database.");
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not connect to MySQL database.", e);
            closeQuietly(connection);
            connection = null;
            return false;
        }
    }

    public synchronized void disconnect() {
        if (connection != null) {
            try {
                connection.close();
                plugin.getLogger().info("Disconnected from MySQL database.");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Error closing MySQL connection.", e);
            } finally {
                connection = null;
            }
        }
    }

    public synchronized Connection getConnection() throws SQLException {
        if (jdbcUrl == null || properties == null) {
            if (!connect()) {
                throw new SQLException("MySQL connection settings are not available.");
            }
        }

        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            closeQuietly(connection);
            connection = DriverManager.getConnection(jdbcUrl, properties);
            createTables(connection);
        }
        return connection;
    }

    private void createTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS rayzwardrobe_armor_sets ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY,"
                    + "player_uuid VARCHAR(36) NOT NULL,"
                    + "set_index INT NOT NULL,"
                    + "name VARCHAR(255) NOT NULL,"
                    + "helmet LONGTEXT,"
                    + "chestplate LONGTEXT,"
                    + "leggings LONGTEXT,"
                    + "boots LONGTEXT,"
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "UNIQUE KEY unique_player_set (player_uuid, set_index)"
                    + ") CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");

            stmt.execute("CREATE TABLE IF NOT EXISTS rayzwardrobe_player_themes ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY,"
                    + "player_uuid VARCHAR(36) NOT NULL UNIQUE,"
                    + "theme_key VARCHAR(255) NOT NULL DEFAULT 'classic',"
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                    + ") CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");

            stmt.execute("CREATE TABLE IF NOT EXISTS rayzwardrobe_tools ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY,"
                    + "player_uuid VARCHAR(36) NOT NULL,"
                    + "set_index INT NOT NULL,"
                    + "tool_index TINYINT NOT NULL,"
                    + "item LONGTEXT,"
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "UNIQUE KEY unique_player_set_tool (player_uuid, set_index, tool_index),"
                    + "INDEX idx_player_set (player_uuid, set_index)"
                    + ") CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private void closeQuietly(Connection conn) {
        if (conn == null) return;
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
