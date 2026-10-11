package dev.grants.storage;

import dev.grants.GrantsPlugin;
import dev.grants.util.ConfigFile;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/** Reads database.yml (optionally filled in from LuckPerms' config.yml) and connects. */
public final class StorageSetup {

    private StorageSetup() {}

    /** Returns a connected database, or null to stay on local grants.yml. Blocking; runs once at startup. */
    public static SqlDatabase connect(GrantsPlugin plugin, ConfigFile databaseFile) {
        YamlConfiguration db = databaseFile.get();
        File lpFolder = luckPermsFolder();

        if (db.getBoolean("use-luckperms-settings", true)) copyFromLuckPerms(plugin, databaseFile, lpFolder);

        String typeName = db.getString("type", "local").toLowerCase(Locale.ROOT).trim();
        SqlDatabase.Type type;
        if (typeName.equals("mysql")) type = SqlDatabase.Type.MYSQL;
        else if (typeName.equals("mariadb")) type = SqlDatabase.Type.MARIADB;
        else if (typeName.equals("postgresql") || typeName.equals("postgres")) type = SqlDatabase.Type.POSTGRESQL;
        else {
            plugin.getLogger().info("Storing grants locally in grants.yml (this server only).");
            return null;
        }

        Map<String, Object> props = new LinkedHashMap<String, Object>();
        ConfigurationSection p = db.getConfigurationSection("properties");
        if (p != null) props.putAll(p.getValues(false));

        SqlDatabase sql = new SqlDatabase(plugin.getLogger(), type,
                db.getString("address", "localhost"),
                db.getString("database", "minecraft"),
                db.getString("username", "root"),
                db.getString("password", ""),
                db.getString("table-prefix", "grants_"),
                props);
        try {
            sql.connect(lpFolder == null ? null : new File(lpFolder, "libs"));
            plugin.getLogger().info("Storing grants in the shared " + typeName + " database.");
            return sql;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not connect to the " + typeName + " database ("
                    + e.getMessage() + "). Falling back to local grants.yml for now.", e);
            sql.close();
            return null;
        }
    }

    private static File luckPermsFolder() {
        Plugin lp = Bukkit.getPluginManager().getPlugin("LuckPerms");
        return lp == null ? null : lp.getDataFolder();
    }

    /** Copies storage-method + data.* from plugins/LuckPerms/config.yml into database.yml. */
    private static void copyFromLuckPerms(GrantsPlugin plugin, ConfigFile databaseFile, File lpFolder) {
        File lpConfig = lpFolder == null ? null : new File(lpFolder, "config.yml");
        if (lpConfig == null || !lpConfig.exists()) {
            plugin.getLogger().warning("use-luckperms-settings is on, but plugins/LuckPerms/config.yml wasn't found.");
            return;
        }
        YamlConfiguration lp = YamlConfiguration.loadConfiguration(lpConfig);
        String method = lp.getString("storage-method", "h2").toLowerCase(Locale.ROOT).trim();
        String type = method.equals("mysql") || method.equals("mariadb") || method.equals("postgresql") ? method : "local";
        if (type.equals("local") && !method.equals("h2") && !method.equals("sqlite")) {
            plugin.getLogger().info("LuckPerms uses '" + method + "', which Grants can't share; using local grants.yml.");
        }

        YamlConfiguration db = databaseFile.get();
        db.set("type", type);
        if (!type.equals("local")) {
            db.set("address", lp.getString("data.address", "localhost"));
            db.set("database", lp.getString("data.database", "minecraft"));
            db.set("username", lp.getString("data.username", "root"));
            db.set("password", lp.getString("data.password", ""));
            ConfigurationSection props = lp.getConfigurationSection("data.pool-settings.properties");
            if (props != null) {
                db.set("properties", null);
                for (Map.Entry<String, Object> e : props.getValues(false).entrySet()) db.set("properties." + e.getKey(), e.getValue());
            }
        }
        databaseFile.save();
    }
}
