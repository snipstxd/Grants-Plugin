package dev.grants.model;

import dev.grants.GrantsPlugin;
import dev.grants.storage.SqlDatabase;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Grant history. Two modes:
 * - local: everything in grants.yml, loaded once.
 * - database: shared with other servers; a player's grants are (re)loaded before every menu opens.
 * The in-memory map is only touched on the main thread.
 */
public final class GrantStore {

    /** Grants younger than this aren't marked "removed" when LuckPerms doesn't show them yet (other server may lag). */
    private static final long RECONCILE_GRACE_MS = 60_000L;

    private final GrantsPlugin plugin;
    private final File file;
    private final Map<UUID, Grant> grants = new LinkedHashMap<UUID, Grant>();
    private SqlDatabase sql;

    public GrantStore(GrantsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "grants.yml");
    }

    // ---------------------------------------------------------------- modes

    public void loadLocal() {
        grants.clear();
        for (Grant g : readYaml(file)) grants.put(g.id(), g);
    }

    /** Switch to the shared database. Any grants.yml from before is copied in once, then renamed. */
    public void useDatabase(SqlDatabase database) {
        this.sql = database;
        grants.clear();
        if (!file.exists()) return;
        final List<Grant> old = readYaml(file);
        final File done = new File(plugin.getDataFolder(), "grants-imported.yml");
        sql.async(() -> {
            for (Grant g : old) sql.saveGrant(g);
            if (done.exists()) done.delete();
            if (file.renameTo(done)) {
                plugin.getLogger().info("Copied " + old.size() + " grants from grants.yml into the database "
                        + "(old file kept as grants-imported.yml).");
            }
        });
    }

    public boolean shared() {
        return sql != null;
    }

    public SqlDatabase database() {
        return sql;
    }

    /** Makes sure this player's grants are fresh (from the database), then runs {@code then} on the main thread. */
    public void loadPlayer(final UUID player, final Runnable then) {
        if (sql == null) {
            then.run();
            return;
        }
        sql.async(() -> {
            final List<Grant> rows;
            try {
                rows = sql.loadGrants(player);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not load grants for " + player + ": " + e.getMessage());
                plugin.sync(then);
                return;
            }
            plugin.sync(() -> {
                grants.values().removeIf(g -> g.target().equals(player));
                for (Grant g : rows) grants.put(g.id(), g);
                tickExpiry(System.currentTimeMillis());
                then.run();
            });
        });
    }

    // ---------------------------------------------------------------- reads

    /** Active grants first (newest first), then expired/removed (most recently ended first). */
    public List<Grant> history(UUID player) {
        List<Grant> list = new ArrayList<Grant>();
        for (Grant g : grants.values()) if (g.target().equals(player)) list.add(g);
        Collections.sort(list, Comparator.comparing((Grant g) -> !g.isActive())
                .thenComparing(g -> g.isActive() ? -g.grantedAt() : -g.endedAt()));
        return list;
    }

    public List<Grant> active(UUID player) {
        List<Grant> list = new ArrayList<Grant>();
        for (Grant g : history(player)) if (g.isActive()) list.add(g);
        return list;
    }

    public Grant get(UUID id) {
        return grants.get(id);
    }

    /** True if an active grant for this rank with this expiry is already recorded. */
    public boolean hasActive(UUID player, String group, long expiresAt) {
        for (Grant g : grants.values()) {
            if (g.isActive() && g.target().equals(player) && g.group().equalsIgnoreCase(group)
                    && Math.abs(g.expiresAt() - expiresAt) < 2000L) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- writes

    public void add(Grant grant) {
        grants.put(grant.id(), grant);
        persist(Collections.singletonList(grant));
    }

    /** Call after changing a grant (e.g. removing it). */
    public void update(Grant grant) {
        persist(Collections.singletonList(grant));
    }

    /** Marks grants whose time has run out as expired. */
    public void tickExpiry(long now) {
        List<Grant> changed = new ArrayList<Grant>();
        for (Grant g : grants.values()) {
            if (g.isActive() && !g.isPermanent() && g.expiresAt() <= now) {
                g.expire();
                changed.add(g);
            }
        }
        if (!changed.isEmpty()) persist(changed);
    }

    /**
     * Brings a player's grants in line with what LuckPerms actually says they have,
     * so a rank removed with /lp shows up as removed here too.
     */
    public void reconcile(UUID player, Set<String> currentGroups, long now, String removedBy) {
        List<Grant> changed = new ArrayList<Grant>();
        for (Grant g : grants.values()) {
            if (!g.target().equals(player) || !g.isActive()) continue;
            if (!g.isPermanent() && g.expiresAt() <= now) {
                g.expire();
                changed.add(g);
            } else if (!currentGroups.contains(g.group().toLowerCase(Locale.ROOT))
                    && now - g.grantedAt() > RECONCILE_GRACE_MS) {
                g.remove(removedBy, now);
                changed.add(g);
            }
        }
        if (!changed.isEmpty()) persist(changed);
    }

    /** Flush to grants.yml (local mode only; the database is written as changes happen). */
    public void save() {
        if (sql == null) writeYaml();
    }

    private void persist(Collection<Grant> changed) {
        final java.util.Set<UUID> players = new java.util.LinkedHashSet<UUID>();
        for (Grant g : changed) players.add(g.target());
        // Redraw any history/remove menus open on this server for these players.
        plugin.sync(() -> {
            for (UUID p : players) plugin.grantsChanged(p);
        });
        if (sql == null) {
            writeYaml();
            return;
        }
        final List<Grant> copies = new ArrayList<Grant>();
        for (Grant g : changed) copies.add(g.copy());
        sql.async(() -> {
            for (Grant g : copies) sql.saveGrant(g);
            // ...and tell the other servers to redraw theirs.
            for (UUID p : players) sql.sendMessage(SqlDatabase.BROADCAST, "grants:" + p + ":" + plugin.serverId());
        });
    }

    // ---------------------------------------------------------------- grants.yml

    private List<Grant> readYaml(File from) {
        List<Grant> out = new ArrayList<Grant>();
        if (!from.exists()) return out;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(from);
        ConfigurationSection root = yaml.getConfigurationSection("grants");
        if (root == null) return out;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            try {
                out.add(new Grant(
                        UUID.fromString(key),
                        UUID.fromString(s.getString("target")),
                        s.getString("target-name", "Unknown"),
                        s.getString("group"),
                        s.getString("granted-by", "Unknown"),
                        s.getLong("granted-at"),
                        s.getLong("expires-at"),
                        upgradeReason(s.getString("reason", "None")),
                        Grant.Status.valueOf(s.getString("status", "ACTIVE")),
                        s.getLong("ended-at"),
                        s.getString("removed-by")));
            } catch (Exception e) {
                plugin.getLogger().warning("Skipping broken grant entry " + key + ": " + e.getMessage());
            }
        }
        return out;
    }

    private static String upgradeReason(String reason) {
        if (reason.equals("done by luckperms command")) return "Done By LuckPerms Command";
        if (reason.equals("rank given by console")) return "Rank Given By Console";
        return reason;
    }

    private void writeYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Grant g : grants.values()) {
            String p = "grants." + g.id() + ".";
            yaml.set(p + "target", g.target().toString());
            yaml.set(p + "target-name", g.targetName());
            yaml.set(p + "group", g.group());
            yaml.set(p + "granted-by", g.grantedBy());
            yaml.set(p + "granted-at", g.grantedAt());
            yaml.set(p + "expires-at", g.expiresAt());
            yaml.set(p + "reason", g.reason());
            yaml.set(p + "status", g.status().name());
            yaml.set(p + "ended-at", g.endedAt());
            yaml.set(p + "removed-by", g.removedBy());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save grants.yml", e);
        }
    }
}
