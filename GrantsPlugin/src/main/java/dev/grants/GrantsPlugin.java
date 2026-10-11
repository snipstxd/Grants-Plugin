package dev.grants;

import dev.grants.command.GrantsCommand;
import dev.grants.gui.MenuListener;
import dev.grants.model.GrantStore;
import dev.grants.storage.SqlDatabase;
import dev.grants.storage.StorageSetup;
import dev.grants.util.ConfigFile;
import dev.grants.util.Durations;
import dev.grants.util.Text;
import dev.grants.util.Version;
import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GrantsPlugin extends JavaPlugin {

    private final ConfigFile settings = new ConfigFile(this, "config.yml");
    private final ConfigFile messages = new ConfigFile(this, "messages.yml");
    private final ConfigFile gui = new ConfigFile(this, "gui.yml");
    private final ConfigFile ranks = new ConfigFile(this, "ranks.yml");
    private final ConfigFile durations = new ConfigFile(this, "durations.yml");
    private final ConfigFile reasons = new ConfigFile(this, "reasons.yml");
    private final ConfigFile database = new ConfigFile(this, "database.yml");

    /** Last cross-server message seen (only touched on the database thread). */
    private volatile long lastMessageId;
    /** Identifies this server in broadcasts so it can skip its own. */
    private final String serverId = UUID.randomUUID().toString();
    private volatile long lastCleanup;
    private final AtomicBoolean polling = new AtomicBoolean();

    /** 1.8 sound names for the default sounds. */
    private static final Map<String, String> LEGACY_SOUNDS = new HashMap<String, String>();

    static {
        LEGACY_SOUNDS.put("ui.button.click", "random.click");
        LEGACY_SOUNDS.put("entity.player.levelup", "random.levelup");
        LEGACY_SOUNDS.put("entity.villager.no", "mob.villager.no");
        LEGACY_SOUNDS.put("entity.experience_orb.pickup", "random.orb");
        LEGACY_SOUNDS.put("block.note_block.pling", "note.pling");
    }

    private GrantStore store;
    private GrantService service;
    private ChatInput chatInput;
    private LuckPermsListener lpListener;

    @Override
    public void onEnable() {
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null) {
            getLogger().severe("LuckPerms not found - disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        YamlConfiguration legacy = takeLegacyConfig();
        reloadFiles();
        if (legacy != null) importLegacy(legacy);

        store = new GrantStore(this);
        database.load();
        SqlDatabase sql = StorageSetup.connect(this, database);
        if (sql != null) {
            store.useDatabase(sql);
            pullColors(sql);
            startMessaging(sql);
        } else {
            store.loadLocal();
        }
        service = new GrantService(this, provider.getProvider());
        chatInput = new ChatInput(this);
        lpListener = new LuckPermsListener(this);
        lpListener.register(provider.getProvider());

        Bukkit.getPluginManager().registerEvents(new MenuListener(this), this);
        Bukkit.getPluginManager().registerEvents(chatInput, this);

        GrantsCommand command = new GrantsCommand(this);
        PluginCommand cmd = getCommand("grants");
        if (cmd != null) {
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }

        service.loadGroups();
        getLogger().info("Running on Minecraft " + Version.describe()
                + (Version.legacyMaterials() ? " (legacy item names)" : ""));
        // Flip timed-out grants to "expired" every 10 seconds.
        Bukkit.getScheduler().runTaskTimer(this, () -> store.tickExpiry(System.currentTimeMillis()), 20L, 200L);
    }

    @Override
    public void onDisable() {
        if (store != null) {
            store.save();
            if (store.database() != null) store.database().close();
        }
    }

    // ---------------------------------------------------------------- cross-server messages

    /** Every few seconds, pick up messages other servers left for players who are on this one. */
    private void startMessaging(final SqlDatabase sql) {
        sql.async(() -> lastMessageId = sql.latestMessageId());
        long ticks = Math.max(1, database.get().getInt("sync-interval", 2)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!polling.compareAndSet(false, true)) return;
            sql.async(() -> {
                try {
                    final List<SqlDatabase.Message> incoming = sql.messagesAfter(lastMessageId);
                    if (!incoming.isEmpty()) {
                        lastMessageId = incoming.get(incoming.size() - 1).id;
                        sync(() -> {
                            for (SqlDatabase.Message m : incoming) {
                                if (SqlDatabase.BROADCAST.equals(m.target)) {
                                    handleBroadcast(m.text);
                                    continue;
                                }
                                Player p = Bukkit.getPlayer(m.target);
                                if (p != null) p.sendMessage(Text.color(m.text));
                            }
                        });
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastCleanup > 60_000L) {
                        lastCleanup = now;
                        sql.deleteMessagesBefore(now - 120_000L);
                    }
                } finally {
                    polling.set(false);
                }
            });
        }, ticks, ticks);
    }

    /**
     * Makes ranks.yml match the shared colours table (the database wins). Colours only this server knows
     * (e.g. set before the database was used) are pushed up so the other servers get them too.
     */
    public void pullColors(final SqlDatabase sql) {
        final Map<String, String> local = new HashMap<String, String>();
        ConfigurationSection section = ranks().getConfigurationSection("colors");
        if (section != null) {
            for (String rank : section.getKeys(false)) local.put(rank.toLowerCase(java.util.Locale.ROOT), section.getString(rank));
        }
        sql.async(() -> {
            final Map<String, String> shared = sql.loadColors();
            for (Map.Entry<String, String> e : local.entrySet()) {
                if (!shared.containsKey(e.getKey()) && e.getValue() != null) sql.saveColor(e.getKey(), e.getValue());
            }
            sync(() -> {
                boolean changed = false;
                for (Map.Entry<String, String> e : shared.entrySet()) {
                    if (!e.getValue().equals(ranks().getString("colors." + e.getKey()))) {
                        ranks().set("colors." + e.getKey(), e.getValue());
                        changed = true;
                    }
                }
                if (changed) ranks.save();
            });
        });
    }

    public String serverId() {
        return serverId;
    }

    /** A player's grants changed on this server: redraw menus showing them. */
    public void grantsChanged(UUID player) {
        dev.grants.gui.Menu.refreshViewers(player);
    }

    /** Sets a rank's wool colour here, and on every other server when the database is shared. */
    public void setRankColor(final String rank, final String color) {
        ranks().set("colors." + rank, color);
        ranks.save();
        final SqlDatabase sql = store.database();
        if (sql == null) return;
        sql.async(() -> {
            sql.saveColor(rank, color);
            sql.sendMessage(SqlDatabase.BROADCAST, "color:" + rank + ":" + color);
        });
    }

    /** Instructions from other servers (sent with SqlDatabase.BROADCAST). */
    private void handleBroadcast(String text) {
        String[] parts = text.split(":", 3);
        if (parts.length == 3 && parts[0].equals("grants")) {
            // Another server changed this player's grants: reload them if anyone here is looking.
            if (parts[2].equals(serverId)) return;
            try {
                final UUID player = UUID.fromString(parts[1]);
                if (dev.grants.gui.Menu.hasViewers(player)) store.loadPlayer(player, () -> dev.grants.gui.Menu.refreshViewers(player));
            } catch (IllegalArgumentException ignored) {
                // malformed
            }
            return;
        }
        if (parts.length == 3 && parts[0].equals("color")) {
            if (!parts[2].equals(ranks().getString("colors." + parts[1]))) {
                ranks().set("colors." + parts[1], parts[2]);
                ranks.save();
            }
        }
    }

    /**
     * Shows a message to a player wherever they are: directly if they're on this server, otherwise through
     * the shared database so the server they're on shows it (needs MySQL/MariaDB/PostgreSQL storage).
     */
    public void notifyPlayer(final UUID player, final String message) {
        if (message == null) return;
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            online.sendMessage(Text.color(message));
            return;
        }
        final SqlDatabase sql = store.database();
        if (sql != null) sql.async(() -> sql.sendMessage(player, message));
    }

    // ---------------------------------------------------------------- files

    /** Loads (or creates) every .yml file and applies the settings that are cached. */
    public void reloadFiles() {
        getDataFolder().mkdirs();
        settings.load();
        messages.load();
        gui.load();
        ranks.load();
        durations.load();
        reasons.load();

        Text.configureDates(settings().getString("date-format"), settings().getString("timezone"));
        ConfigurationSection t = messages().getConfigurationSection("time");
        if (t != null) {
            Durations.setWords(new String[]{
                    t.getString("permanent"), t.getString("day"), t.getString("days"),
                    t.getString("hour"), t.getString("hours"), t.getString("minute"),
                    t.getString("minutes"), t.getString("second"), t.getString("seconds")});
        }
    }

    public YamlConfiguration settings() { return settings.get(); }
    public YamlConfiguration messages() { return messages.get(); }
    public YamlConfiguration gui() { return gui.get(); }
    public YamlConfiguration ranks() { return ranks.get(); }
    public YamlConfiguration durations() { return durations.get(); }
    public YamlConfiguration reasons() { return reasons.get(); }
    public ConfigFile ranksFile() { return ranks; }

    /**
     * Versions up to 1.0.6 kept everything in one config.yml. If that's what is on disk,
     * move it to config-old.yml so the new split files get created, then copy the settings over.
     */
    private YamlConfiguration takeLegacyConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) return null;
        YamlConfiguration old = YamlConfiguration.loadConfiguration(file);
        if (!old.isConfigurationSection("messages")) return null;
        File backup = new File(getDataFolder(), "config-old.yml");
        if (backup.exists()) backup.delete();
        if (!file.renameTo(backup)) {
            getLogger().warning("Could not rename the old config.yml; leaving it in place.");
            return null;
        }
        return old;
    }

    private void importLegacy(YamlConfiguration old) {
        copy(old, "date-format", settings(), "date-format");
        copy(old, "timezone", settings(), "timezone");
        copy(old, "default-group", settings(), "default-group");
        copy(old, "lp-command-reason", settings(), "lp-tracking.command-reason");
        copy(old, "lp-console-reason", settings(), "lp-tracking.console-reason");
        copy(old, "rank-colors", ranks(), "colors");
        copy(old, "hidden-groups", ranks(), "hidden");
        copy(old, "duration-presets", durations(), "presets");
        copy(old, "reasons", reasons(), "reasons");

        // Messages: keep the ones the owner customised; skip old defaults that have since changed.
        Set<String> oldDefaults = new HashSet<String>(Arrays.asList("&8[&6Grants&8] ", "&b&l[Grants]&r ",
                "&aSuccessfully Granted &f{player} &arank {prefix} &afor: &f{duration} &areason: &f{reason}",
                "&aRemoved {prefix} &afrom &f{player} &asuccessfully.",
                "&eType the duration in chat (e.g. &f30m&e, &f2h&e, &f7d&e, &f1d12h&e). Type &ccancel &eto go back.",
                "&c'&f{input}&c' is not a valid duration. Try again, or type &fcancel&c.",
                "&eType the reason in chat. Type &ccancel &eto go back.",
                "&aConfig reloaded."));
        ConfigurationSection oldMessages = old.getConfigurationSection("messages");
        for (String key : oldMessages.getKeys(false)) {
            String value = oldMessages.getString(key);
            if (value == null || oldDefaults.contains(value) || !messages().isSet(key)) continue;
            messages().set(key, value);
        }

        settings.save();
        messages.save();
        ranks.save();
        durations.save();
        reasons.save();
        getLogger().info("Moved settings from the old config.yml into the new files (backup: config-old.yml).");
    }

    private static void copy(YamlConfiguration from, String fromPath, YamlConfiguration to, String toPath) {
        if (from.isSet(fromPath)) to.set(toPath, from.get(fromPath));
    }

    // ---------------------------------------------------------------- services

    public GrantStore store() { return store; }
    public GrantService service() { return service; }
    public ChatInput chatInput() { return chatInput; }
    public LuckPermsListener lpListener() { return lpListener; }

    public void sync(Runnable task) {
        if (!isEnabled()) return;
        Bukkit.getScheduler().runTask(this, task);
    }

    // ---------------------------------------------------------------- output

    /** Sends a messages.yml entry (with the prefix) and fills in {placeholders}. Empty messages are not sent. */
    public void send(CommandSender to, String key, String... placeholders) {
        String msg = format(key, placeholders);
        if (msg != null) to.sendMessage(Text.color(msg));
    }

    /** A messages.yml entry with the prefix and {placeholders} filled in (colour codes not yet applied), or null if empty. */
    public String format(String key, String... placeholders) {
        String msg = messages().getString(key, "");
        if (msg.isEmpty()) return null;
        msg = Text.apply(msg, placeholders);
        msg = Text.apply(msg, "cancel", cancelWord());
        return messages().getString("prefix", "") + msg;
    }

    /** Sends each line of a messages.yml list as-is (no prefix). */
    public void sendLines(CommandSender to, String key, String... placeholders) {
        List<String> lines = messages().getStringList(key);
        for (String line : lines) to.sendMessage(Text.color(Text.apply(line, placeholders)));
    }

    public String cancelWord() {
        return settings().getString("cancel-word", "cancel");
    }

    /** Plays click / success / error from config.yml -> sounds. */
    public void sound(Player player, String which) {
        if (!settings().getBoolean("sounds.enabled", true)) return;
        String sound = settings().getString("sounds." + which, "");
        if (sound.trim().isEmpty()) return;
        if (!Version.modernSounds() && LEGACY_SOUNDS.containsKey(sound)) sound = LEGACY_SOUNDS.get(sound);
        try {
            player.playSound(player.getLocation(), sound, 1f, 1f);
        } catch (RuntimeException ignored) {
            // Misspelled sound names shouldn't break anything.
        }
    }
}
