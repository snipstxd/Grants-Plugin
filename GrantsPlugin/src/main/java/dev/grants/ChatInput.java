package dev.grants;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Captures the next chat message of a player (for custom durations / reasons). */
public final class ChatInput implements Listener {

    private final GrantsPlugin plugin;
    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    public ChatInput(GrantsPlugin plugin) {
        this.plugin = plugin;
    }

    /** The handler runs on the main thread with the trimmed message. */
    public void await(Player player, Consumer<String> handler) {
        pending.put(player.getUniqueId(), handler);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Consumer<String> handler = pending.remove(event.getPlayer().getUniqueId());
        if (handler == null) return;
        event.setCancelled(true);
        String message = event.getMessage().trim();
        plugin.sync(() -> handler.accept(message));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
