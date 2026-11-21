package dev.grants;

import dev.grants.storage.SqlDatabase;
import dev.grants.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * "Your rank has now been set to..." for players who were offline when it happened: shown when they next join.
 * Kept in the shared database (so any server can deliver it) or, without one, in pending.yml on this server.
 */
public final class PendingMessages implements Listener {

    /** Wait a moment after joining so the message isn't buried under join messages. */
    private static final long JOIN_DELAY_TICKS = 20L;

    private final GrantsPlugin plugin;
    private final File file;
    private final YamlConfiguration local;

    PendingMessages(GrantsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pending.yml");
        this.local = YamlConfiguration.loadConfiguration(file);
    }

    /** Saves a message for a player who isn't on this server. */
    public void add(final UUID player, final String message) {
        final SqlDatabase sql = plugin.store().database();
        if (sql != null) {
            sql.async(() -> sql.addPending(player, message));
            return;
        }
        List<String> list = local.getStringList(player.toString());
        list.add(message);
        local.set(player.toString(), list);
        save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID id = player.getUniqueId();
        final SqlDatabase sql = plugin.store().database();
        if (sql != null) {
            sql.async(() -> {
                List<String> messages = sql.takePending(id);
                if (!messages.isEmpty()) deliverLater(player, messages);
            });
            return;
        }
        List<String> messages = local.getStringList(id.toString());
        if (messages.isEmpty()) return;
        local.set(id.toString(), null);
        save();
        deliverLater(player, messages);
    }

    private void deliverLater(final Player player, final List<String> messages) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                for (String m : messages) player.sendMessage(Text.color(m));
                return;
            }
            // Left again straight away: keep them for next time.
            for (String m : messages) add(player.getUniqueId(), m);
        }, JOIN_DELAY_TICKS);
    }

    private void save() {
        try {
            local.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save pending.yml: " + e.getMessage());
        }
    }
}
