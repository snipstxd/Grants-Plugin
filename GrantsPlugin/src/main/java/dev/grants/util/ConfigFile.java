package dev.grants.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/**
 * One editable .yml file in plugins/Grants. Missing files are created from the jar, and settings added
 * in newer versions are filled in on load without touching anything the server owner changed.
 */
public final class ConfigFile {

    private final JavaPlugin plugin;
    private final String name;
    private File file;
    private YamlConfiguration yaml = new YamlConfiguration();

    public ConfigFile(JavaPlugin plugin, String name) {
        this.plugin = plugin;
        this.name = name;
    }

    public void load() {
        file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);
        yaml = YamlConfiguration.loadConfiguration(file);

        InputStream in = plugin.getResource(name);
        if (in == null) return;
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        yaml.setDefaults(defaults);

        boolean missing = false;
        for (String key : defaults.getKeys(true)) {
            if (!defaults.isConfigurationSection(key) && !yaml.isSet(key)) {
                missing = true;
                break;
            }
        }
        if (missing) {
            yaml.options().copyDefaults(true);
            save();
        }
    }

    public YamlConfiguration get() {
        return yaml;
    }

    public void save() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + name, e);
        }
    }
}
