package dev.grants;

import dev.grants.model.Grant;
import dev.grants.util.Text;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.actionlog.Action;
import net.luckperms.api.event.EventBus;
import net.luckperms.api.event.log.LogPublishEvent;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.event.node.NodeClearEvent;
import net.luckperms.api.event.node.NodeRemoveEvent;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records ranks given or taken with /lp (or the web editor) so they show up in /grants history.
 * Node events tell us what changed; the action log tells us who did it.
 */
public final class LuckPermsListener {

    private static final UUID CONSOLE_UUID = new UUID(0, 0);
    /** Wait this long before recording, so the matching action-log entry has arrived. */
    private static final long DELAY_TICKS = 40L;
    private static final long SOURCE_WINDOW_MS = 10_000L;

    private static final class Source {
        private final String name;
        private final boolean console;
        private final long at;

        Source(String name, boolean console, long at) {
            this.name = name;
            this.console = console;
            this.at = at;
        }

        String name() { return name; }
        boolean console() { return console; }
        long at() { return at; }
    }

    private final GrantsPlugin plugin;
    private final Map<UUID, Source> recentSources = new ConcurrentHashMap<>();
    /** Last person who ran a LuckPerms action on each player, kept for the whole session. */
    private final Map<UUID, Source> lastSources = new ConcurrentHashMap<>();

    public LuckPermsListener(GrantsPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(LuckPerms lp) {
        EventBus bus = lp.getEventBus();
        bus.subscribe(plugin, LogPublishEvent.class, this::onLog);
        bus.subscribe(plugin, NodeAddEvent.class, this::onAdd);
        bus.subscribe(plugin, NodeRemoveEvent.class, e -> {
            if (e.getTarget() instanceof User) onTaken((User) e.getTarget(), java.util.Collections.singletonList(e.getNode()));
        });
        bus.subscribe(plugin, NodeClearEvent.class, e -> {
            if (e.getTarget() instanceof User) onTaken((User) e.getTarget(), e.getNodes());
        });
    }

    private boolean enabled() {
        return plugin.settings().getBoolean("lp-tracking.enabled", true);
    }

    private void onLog(LogPublishEvent event) {
        Action action = event.getEntry();
        if (action.getTarget().getType() != Action.Target.Type.USER) return;
        action.getTarget().getUniqueId().ifPresent(target -> {
            Action.Source src = action.getSource();
            boolean console = CONSOLE_UUID.equals(src.getUniqueId()) || "console".equalsIgnoreCase(src.getName());
            Source source = new Source(console ? "Console" : src.getName(), console, System.currentTimeMillis());
            recentSources.put(target, source);
            lastSources.put(target, source);
            if (!enabled()) return;
            // Any rank this action took away gets credited to whoever ran it.
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> plugin.service().refresh(target, source.name(), () -> { }), 10L);
        });
    }

    private Source sourceFor(UUID target) {
        Source s = recentSources.get(target);
        if (s != null && System.currentTimeMillis() - s.at() <= SOURCE_WINDOW_MS) return s;
        return new Source("Console", true, System.currentTimeMillis());
    }

    /** Who to credit for a removal we only noticed later: the last LuckPerms actor on this player. */
    public String lastActor(UUID target) {
        Source s = lastSources.get(target);
        return s != null ? s.name() : "Console";
    }

    private void onAdd(NodeAddEvent event) {
        if (!enabled()) return;
        if (!(event.getTarget() instanceof User)) return;
        if (!(event.getNode() instanceof InheritanceNode)) return;
        User user = (User) event.getTarget();
        InheritanceNode node = (InheritanceNode) event.getNode();
        if (!node.getContexts().isEmpty() || node.hasExpired()) return;

        UUID uuid = user.getUniqueId();
        String group = node.getGroupName();
        String name = user.getUsername() != null ? user.getUsername() : uuid.toString();
        Instant expiry = node.getExpiry();
        long expiresAt = expiry == null ? 0L : expiry.toEpochMilli();
        long grantedAt = System.currentTimeMillis();

        if (plugin.service().isOwnChange(uuid, group)) return;
        String defaultGroup = plugin.settings().getString("default-group", "default");
        if (group.equalsIgnoreCase(defaultGroup)) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.store().loadPlayer(uuid, () -> {
            if (plugin.store().hasActive(uuid, group, expiresAt)) return;
            Source src = sourceFor(uuid);
            String reason = Text.titleCase(src.console()
                    ? plugin.settings().getString("lp-tracking.console-reason", "Rank Given By Console")
                    : plugin.settings().getString("lp-tracking.command-reason", "Done By LuckPerms Command"));
            plugin.store().add(new Grant(UUID.randomUUID(), uuid, name, group, src.name(),
                    grantedAt, expiresAt, reason, Grant.Status.ACTIVE, 0, null));
        }), DELAY_TICKS);
    }

    private void onTaken(User user, Collection<Node> nodes) {
        if (!enabled()) return;
        UUID uuid = user.getUniqueId();
        boolean relevant = false;
        for (Node n : nodes) {
            if (n instanceof InheritanceNode && !plugin.service().isOwnChange(uuid, ((InheritanceNode) n).getGroupName())) {
                relevant = true;
                break;
            }
        }
        if (!relevant) return;
        // Re-check against LuckPerms after the log entry arrives; expired ranks are marked expired, not removed.
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> plugin.service().refresh(uuid, sourceFor(uuid).name(), () -> { }), DELAY_TICKS);
    }
}
