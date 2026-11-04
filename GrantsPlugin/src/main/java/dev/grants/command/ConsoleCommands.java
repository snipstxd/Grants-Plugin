package dev.grants.command;

import dev.grants.GrantService;
import dev.grants.GrantService.Target;
import dev.grants.GrantsPlugin;
import dev.grants.gui.GrantItems;
import dev.grants.model.Grant;
import dev.grants.util.Durations;
import net.luckperms.api.model.group.Group;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** /grants add, remove and history from the console (or a command block): typed out in full instead of menus. */
final class ConsoleCommands {

    private final GrantsPlugin plugin;

    ConsoleCommands(GrantsPlugin plugin) {
        this.plugin = plugin;
    }

    /** /grants add <player> <rank> <duration> <reason...> */
    void add(CommandSender sender, String label, String[] args) {
        if (args.length < 5) {
            plugin.send(sender, "console-usage-add", "label", label);
            return;
        }
        final Group group = grantableGroup(args[2]);
        if (group == null) {
            plugin.send(sender, "unknown-rank", "rank", args[2]);
            return;
        }
        final long duration = Durations.parse(args[3]);
        if (duration == Durations.INVALID) {
            plugin.send(sender, "console-invalid-duration", "input", args[3]);
            return;
        }
        final String reason = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
        withTarget(sender, args[1], t -> plugin.service().grant(actor(sender), t, group.getName(), duration, reason));
    }

    /** /grants remove <player> <rank> */
    void remove(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            plugin.send(sender, "console-usage-remove", "label", label);
            return;
        }
        final String rank = args[2];
        withTarget(sender, args[1], t -> {
            List<Grant> grants = new ArrayList<Grant>();
            for (Grant g : plugin.store().active(t.uuid())) {
                if (g.group().equalsIgnoreCase(rank)) grants.add(g);
            }
            if (grants.isEmpty()) {
                plugin.send(sender, "console-not-granted", "player", t.name(), "rank", rank);
                return;
            }
            plugin.service().remove(actor(sender), grants);
        });
    }

    /** /grants history <player>: one line per grant. */
    void history(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            plugin.send(sender, "usage-player", "label", label, "command", "history");
            return;
        }
        withTarget(sender, args[1], t -> {
            List<Grant> grants = plugin.store().history(t.uuid());
            plugin.sendRaw(sender, "console-history.header", "player", t.name(), "count", String.valueOf(grants.size()));
            if (grants.isEmpty()) {
                plugin.sendRaw(sender, "console-history.empty", "player", t.name());
                return;
            }
            for (Grant g : grants) {
                String key = g.isActive() ? "console-history.active"
                        : g.removedBy() != null ? "console-history.removed" : "console-history.expired";
                String[] ph = GrantItems.placeholders(plugin, g);
                // {rank} in the rank's LuckPerms colour (its colour code wins over the &f before it in messages.yml).
                for (int i = 0; i < ph.length; i += 2) {
                    if (ph[i].equals("rank")) ph[i + 1] = plugin.service().coloredRank(g.group());
                }
                plugin.sendRaw(sender, key, ph);
            }
        });
    }

    /** config.yml -> console-name with {server} = LuckPerms' "server" setting, e.g. "ffa Console". */
    private String consoleName() {
        String format = plugin.settings().getString("console-name", "{server} Console");
        // "Console" was the first default; treat it as the new one so existing servers get the server name.
        if (format.equals("Console")) format = "{server} Console";
        String server = plugin.service().luckPermsServerName();
        if (server == null || server.trim().isEmpty() || server.equalsIgnoreCase("global")) {
            // No server name set in LuckPerms: just "Console".
            return format.replace("{server}", "").trim();
        }
        return format.replace("{server}", server).trim();
    }

    private Group grantableGroup(String name) {
        for (Group g : plugin.service().grantableGroups()) if (g.getName().equalsIgnoreCase(name)) return g;
        return null;
    }

    /** Resolves the name, loads their grants, then runs {@code action} on the main thread. */
    private void withTarget(final CommandSender sender, final String name, final Consumer<Target> action) {
        plugin.service().resolve(name).whenComplete((target, ex) -> plugin.sync(() -> {
            if (ex != null || target == null) {
                plugin.send(sender, "player-not-found", "player", name);
                return;
            }
            plugin.service().refresh(target.uuid(), () -> action.accept(target));
        }));
    }

    /** The console: no rank hierarchy, saved as config.yml -> console-name. */
    private GrantService.Actor actor(final CommandSender sender) {
        final String name = consoleName();
        return new GrantService.Actor() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean canManage(String group) {
                return true;
            }

            @Override
            public void reply(boolean success, String key, String... placeholders) {
                plugin.send(sender, key, placeholders);
            }
        };
    }
}
