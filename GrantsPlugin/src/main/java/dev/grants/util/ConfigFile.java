package dev.grants.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Level;

/**
 * One editable .yml file in plugins/Grants. Missing files are created from the jar, and settings added
 * in newer versions are filled in on load without touching anything the server owner changed.
 */
public final class ConfigFile {

    private final JavaPlugin plugin;
    private final String name;
    /** Rewrite the file in the jar's order, so the important settings stay at the top. */
    private final boolean templateOrder;
    private File file;
    private YamlConfiguration yaml = new YamlConfiguration();

    public ConfigFile(JavaPlugin plugin, String name) {
        this(plugin, name, false);
    }

    public ConfigFile(JavaPlugin plugin, String name, boolean templateOrder) {
        this.plugin = plugin;
        this.name = name;
        this.templateOrder = templateOrder;
    }

    public void load() {
        file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);
        yaml = YamlConfiguration.loadConfiguration(file);

        InputStream in = plugin.getResource(name);
        if (in == null) return;
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        if (templateOrder) {
            rewriteInTemplateOrder(defaults);
            return;
        }
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
            // Read it back: lookups with a fallback (getString(path, def)) don't see defaults that were only copied.
            yaml = YamlConfiguration.loadConfiguration(file);
            yaml.setDefaults(defaults);
        }
    }

    /**
     * Rebuilds the file from the jar's copy with the owner's values filled in: same order and (where the server
     * keeps them) comments as the default file, new settings added in place, and settings no longer used dropped.
     * Extra entries in lists of named blocks (like discord-servers -> events) are kept.
     */
    private void rewriteInTemplateOrder(YamlConfiguration defaults) {
        InputStream in = plugin.getResource(name);
        if (in == null) return;
        YamlConfiguration ordered = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String key : yaml.getKeys(true)) {
            if (yaml.isConfigurationSection(key)) continue;
            if (defaults.isSet(key) || inNamedBlocks(defaults, key)) ordered.set(key, yaml.get(key));
        }
        String text = ordered.saveToString();
        String current;
        try {
            current = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            current = "";
        }
        yaml = ordered;
        yaml.setDefaults(defaults);
        if (!text.equals(current)) save();
    }

    /** True if {@code key} sits in a section whose default children are all blocks, e.g. discord-servers. */
    private static boolean inNamedBlocks(YamlConfiguration defaults, String key) {
        String path = key;
        int dot;
        while ((dot = path.lastIndexOf('.')) > 0) {
            path = path.substring(0, dot);
            ConfigurationSection section = defaults.getConfigurationSection(path);
            if (section == null || section.getKeys(false).isEmpty()) continue;
            boolean allBlocks = true;
            for (String child : section.getKeys(false)) if (!section.isConfigurationSection(child)) allBlocks = false;
            if (allBlocks) return true;
        }
        return false;
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
