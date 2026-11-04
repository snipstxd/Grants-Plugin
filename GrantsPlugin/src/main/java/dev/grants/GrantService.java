package dev.grants;

import dev.grants.model.Grant;
import dev.grants.util.Durations;
import dev.grants.util.Text;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.data.TemporaryNodeMergeStrategy;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.stream.Collectors;

/** Everything that talks to LuckPerms. Callbacks are always hopped back to the main thread. */
public final class GrantService {

    public static final class Target {
        private final UUID uuid;
        private final String name;

        public Target(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public UUID uuid() { return uuid; }
        public String name() { return name; }
    }

    private final GrantsPlugin plugin;
    private final LuckPerms lp;
    /** uuid:group -> time until which LuckPerms events for it are ours and should be ignored. */
    private final Map<String, Long> ownChanges = new ConcurrentHashMap<>();

    public GrantService(GrantsPlugin plugin, LuckPerms lp) {
        this.plugin = plugin;
        this.lp = lp;
    }

    // ---------------------------------------------------------------- lookups

    /** Finds a player by name: online first, then LuckPerms' records, then Bukkit's. Completes with null if unknown. */
    public CompletableFuture<Target> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return CompletableFuture.completedFuture(new Target(online.getUniqueId(), online.getName()));

        UserManager users = lp.getUserManager();
        return users.lookupUniqueId(name).thenCompose(uuid -> {
            if (uuid != null) {
                return users.lookupUsername(uuid).thenApply(n -> new Target(uuid, n != null ? n : name));
            }
            return CompletableFuture.supplyAsync(() -> {
                @SuppressWarnings("deprecation")
                OfflinePlayer op = Bukkit.getOfflinePlayer(name);
                if (!op.hasPlayedBefore()) return null;
                return new Target(op.getUniqueId(), op.getName() != null ? op.getName() : name);
            });
        });
    }

    public CompletableFuture<Void> loadGroups() {
        return lp.getGroupManager().loadAllGroups();
    }

    /** Groups shown in /grants add, heaviest first. */
    public List<Group> grantableGroups() {
        Set<String> hidden = plugin.ranks().getStringList("hidden").stream()
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        return lp.getGroupManager().getLoadedGroups().stream()
                .filter(g -> !hidden.contains(g.getName().toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparingInt((Group g) -> g.getWeight().orElse(0)).reversed()
                        .thenComparing(Group::getName))
                .collect(Collectors.toList());
    }

    /** The "server" setting from LuckPerms' config.yml ("global" when it hasn't been set). */
    public String luckPermsServerName() {
        return lp.getServerName();
    }

    public Group group(String name) {
        return lp.getGroupManager().getGroup(name);
    }

    public String prefix(String groupName) {
        Group g = group(groupName);
        if (g == null) return "";
        String p = g.getCachedData().getMetaData().getPrefix();
        return p == null ? "" : p;
    }

    public String displayName(String groupName) {
        Group g = group(groupName);
        if (g == null) return groupName;
        String d = g.getDisplayName();
        return d != null ? d : g.getName();
    }

    /** Prefix if the rank has one, otherwise its name. Used in chat messages. */
    public String prefixOrName(String groupName) {
        String p = prefix(groupName);
        return p.trim().isEmpty() ? "&f" + displayName(groupName) : Text.stripTrailing(p);
    }

    /** Player name in the colour their prefix ends with (the colour LuckPerms gives their name), white if none. */
    private static String coloredName(String name, String userPrefix) {
        String color = Text.lastColor(userPrefix);
        return (color != null ? color : "&f") + name;
    }

    /** Calls {@code then} on the main thread with the player's name in their LuckPerms colour. */
    public void coloredName(final Target target, final java.util.function.Consumer<String> then) {
        lp.getUserManager().loadUser(target.uuid()).whenComplete((user, ex) -> plugin.sync(() ->
                then.accept(coloredName(target.name(),
                        user == null ? null : user.getCachedData().getMetaData().getPrefix()))));
    }

    /** Lower-cased names of the groups LuckPerms currently gives this player. */
    public CompletableFuture<Set<String>> currentGroups(UUID uuid) {
        return lp.getUserManager().loadUser(uuid).thenApply(user -> user.getNodes(NodeType.INHERITANCE).stream()
                .filter(n -> !n.hasExpired())
                .map(n -> n.getGroupName().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet()));
    }

    /** Loads LuckPerms data, syncs our records with it, then runs {@code then} on the main thread. */
    public void refresh(UUID uuid, Runnable then) {
        refresh(uuid, plugin.lpListener().lastActor(uuid), then);
    }

    /** Same as {@link #refresh(UUID, Runnable)}, crediting rank removals to {@code removedBy}. */
    public void refresh(UUID uuid, String removedBy, Runnable then) {
        plugin.store().loadPlayer(uuid, () ->
                currentGroups(uuid).whenComplete((groups, ex) -> plugin.sync(() -> {
                    if (ex != null) plugin.getLogger().log(Level.WARNING, "Could not load LuckPerms data for " + uuid, ex);
                    else plugin.store().reconcile(uuid, groups, System.currentTimeMillis(), removedBy);
                    then.run();
                })));
    }

    // ---------------------------------------------------------------- rank hierarchy

    /** Highest weight among all ranks this online player has (directly or inherited). */
    public int weightOf(Player player) {
        User user = lp.getUserManager().getUser(player.getUniqueId());
        if (user == null) return 0;
        return user.getInheritedGroups(user.getQueryOptions()).stream()
                .mapToInt(g -> g.getWeight().orElse(0))
                .max().orElse(0);
    }

    /**
     * Staff may only grant or remove ranks with a lower weight than their own. Only weights count:
     * OP and the * wildcard do not skip this. Whoever holds the heaviest rank on the server can manage every rank.
     */
    public boolean canManage(Player player, String groupName) {
        if (!plugin.settings().getBoolean("rank-hierarchy.enabled", true)) return true;
        Group g = group(groupName);
        int weight = g == null ? 0 : g.getWeight().orElse(0);
        int own = weightOf(player);
        if (weight < own) return true;
        int top = lp.getGroupManager().getLoadedGroups().stream()
                .mapToInt(x -> x.getWeight().orElse(0)).max().orElse(0);
        return own > 0 && own >= top;
    }

    // ---------------------------------------------------------------- own-change tracking

    private static String key(UUID uuid, String group) {
        return uuid + ":" + group.toLowerCase(Locale.ROOT);
    }

    /** LuckPerms posts node events asynchronously, so ignore events for this rank for a few seconds. */
    private void markOwnChange(UUID uuid, String group) {
        ownChanges.put(key(uuid, group), System.currentTimeMillis() + 5000L);
    }

    public boolean isOwnChange(UUID uuid, String group) {
        Long until = ownChanges.get(key(uuid, group));
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            ownChanges.remove(key(uuid, group));
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- actions

    /**
     * Checks (with fresh data) whether the player already has this rank, either as an active grant or directly in
     * LuckPerms. Calls {@code then} on the main thread with the answer and the player's name in their current colour.
     */
    public void checkAlreadyHas(final Target target, final String group, final java.util.function.BiConsumer<Boolean, String> then) {
        plugin.store().loadPlayer(target.uuid(), () ->
                lp.getUserManager().loadUser(target.uuid()).whenComplete((user, ex) -> plugin.sync(() -> {
                    boolean has = false;
                    for (Grant g : plugin.store().active(target.uuid())) {
                        if (g.group().equalsIgnoreCase(group)) has = true;
                    }
                    String prefixNow = null;
                    if (user != null) {
                        prefixNow = user.getCachedData().getMetaData().getPrefix();
                        for (InheritanceNode n : user.getNodes(NodeType.INHERITANCE)) {
                            if (!n.hasExpired() && n.getGroupName().equalsIgnoreCase(group)) has = true;
                        }
                    }
                    then.accept(has, coloredName(target.name(), prefixNow));
                })));
    }

    /** Tells {@code viewer} the player already has this rank. */
    public void sayAlreadyHas(Player viewer, String coloredName, String group) {
        plugin.sound(viewer, "error");
        plugin.send(viewer, "already-granted", "player", coloredName,
                "rank", displayName(group), "prefix", prefixOrName(group));
    }


    /** Whoever asked for a grant or removal: a player in a menu, or someone on Discord. */
    public interface Actor {
        /** Saved as "granted by" / "removed by". */
        String name();

        /** Rank-hierarchy check. Discord users are limited by their role instead. */
        boolean canManage(String group);

        /** Reports a messages.yml entry back to them. */
        void reply(boolean success, String key, String... placeholders);

        /** Name of the Discord server this came from (discord.yml), or null if it was done in-game. */
        default String discordServer() {
            return null;
        }
    }

    /** A player: rank hierarchy applies, replies are chat messages with a sound. */
    public Actor actor(final Player player) {
        return new Actor() {
            @Override
            public String name() {
                return player.getName();
            }

            @Override
            public boolean canManage(String group) {
                return GrantService.this.canManage(player, group);
            }

            @Override
            public void reply(boolean success, String key, String... placeholders) {
                if (!player.isOnline()) return;
                plugin.sound(player, success ? "success" : "error");
                plugin.send(player, key, placeholders);
            }
        };
    }

    public void grant(Player granter, Target target, String group, long duration, String reason) {
        grant(actor(granter), target, group, duration, reason);
    }

    public void grant(final Actor granter, final Target target, final String group, final long duration, final String reason) {
        if (!granter.canManage(group)) {
            granter.reply(false, "rank-too-high");
            return;
        }
        // Last check right before applying: another staff member (on any server) may have just granted it.
        checkAlreadyHas(target, group, (has, name) -> {
            if (has) {
                granter.reply(false, "already-granted", "player", name,
                        "rank", displayName(group), "prefix", prefixOrName(group));
                return;
            }
            apply(granter, target, group, duration, reason);
        });
    }

    private void apply(Actor granter, Target target, String group, long duration, String reason) {
        long now = System.currentTimeMillis();
        long expiresAt = duration > 0 ? now + duration : 0;

        markOwnChange(target.uuid(), group);
        AtomicReference<String> prefixBefore = new AtomicReference<>();
        lp.getUserManager().modifyUser(target.uuid(), user -> {
            prefixBefore.set(user.getCachedData().getMetaData().getPrefix());
            InheritanceNode.Builder node = InheritanceNode.builder(group);
            if (expiresAt > 0) node.expiry(expiresAt / 1000L);
            user.data().add(node.build(), TemporaryNodeMergeStrategy.REPLACE_EXISTING_IF_DURATION_LONGER);
        }).whenComplete((v, ex) -> plugin.sync(() -> {
            if (ex != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to grant " + group + " to " + target.name(), ex);
                granter.reply(false, "error");
                return;
            }
            Grant grant = new Grant(UUID.randomUUID(), target.uuid(), target.name(), group, granter.name(),
                    now, expiresAt, reason, Grant.Status.ACTIVE, 0, null);
            plugin.store().add(grant);
            plugin.discordBot().logGrant(grant, false, granter.discordServer());
            granter.reply(true, "granted",
                    "player", coloredName(target.name(), prefixBefore.get()),
                    "prefix", prefixOrName(group),
                    "rank", displayName(group),
                    "duration", Durations.format(duration),
                    "reason", reason);
            // Reaches the player on whichever server they're on (see GrantsPlugin#notifyPlayer).
            if (plugin.settings().getBoolean("notify-player", true)) {
                plugin.notifyPlayer(target.uuid(), plugin.format(duration > 0 ? "rank-set-temp" : "rank-set",
                        "prefix", prefixOrName(group),
                        "rank", displayName(group),
                        "duration", Durations.format(duration)));
            }
            plugin.getLogger().info(granter.name() + " granted " + group + " to " + target.name()
                    + " for " + Durations.format(duration) + " (" + reason + ")");
        }));
    }

    public void remove(Player remover, Grant grant) {
        // The menu may hold an older copy; use the freshest one we have.
        Grant fresh = plugin.store().get(grant.id());
        if (fresh != null && fresh != grant) {
            remove(remover, fresh);
            return;
        }
        if (!canManage(remover, grant.group())) {
            plugin.sound(remover, "error");
            plugin.send(remover, "remove-too-high");
            return;
        }
        if (!grant.isActive()) {
            plugin.send(remover, "grant-not-active");
            return;
        }
        remove(actor(remover), Collections.singletonList(grant));
    }

    /** Removes active grants that all give the same rank to the same player. */
    public void remove(final Actor remover, final List<Grant> grants) {
        final Grant first = grants.get(0);
        if (!remover.canManage(first.group())) {
            remover.reply(false, "remove-too-high");
            return;
        }
        // If other active grants give the same rank, keep the node alive for the longest of them.
        List<Grant> others = plugin.store().active(first.target()).stream()
                .filter(o -> !grants.contains(o) && o.group().equalsIgnoreCase(first.group()))
                .collect(Collectors.toList());
        Long keepExpiry = null;
        if (!others.isEmpty()) {
            keepExpiry = others.stream().anyMatch(Grant::isPermanent) ? 0L
                    : others.stream().mapToLong(Grant::expiresAt).max().orElse(0L);
        }
        final Long keep = keepExpiry;

        markOwnChange(first.target(), first.group());
        AtomicReference<String> prefixBefore = new AtomicReference<>();
        lp.getUserManager().modifyUser(first.target(), user -> {
            prefixBefore.set(user.getCachedData().getMetaData().getPrefix());
            user.data().clear(n -> n instanceof InheritanceNode
                    && ((InheritanceNode) n).getGroupName().equalsIgnoreCase(first.group())
                    && n.getContexts().isEmpty());
            if (keep != null) {
                InheritanceNode.Builder node = InheritanceNode.builder(first.group());
                if (keep > 0) node.expiry(keep / 1000L);
                user.data().add(node.build());
            }
        }).whenComplete((v, ex) -> plugin.sync(() -> {
            if (ex != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to remove " + first.group() + " from " + first.targetName(), ex);
                remover.reply(false, "error");
                return;
            }
            long now = System.currentTimeMillis();
            for (Grant grant : grants) {
                grant.remove(remover.name(), now);
                plugin.store().update(grant);
            }
            plugin.discordBot().logRemoval(grants, remover.name(), remover.discordServer());
            remover.reply(true, "removed",
                    "player", coloredName(first.targetName(), prefixBefore.get()),
                    "prefix", prefixOrName(first.group()),
                    "rank", displayName(first.group()));
            plugin.getLogger().info(remover.name() + " removed " + first.group() + " from " + first.targetName());
        }));
    }
}
