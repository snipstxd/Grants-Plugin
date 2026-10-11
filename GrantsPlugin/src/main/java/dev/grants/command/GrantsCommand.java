package dev.grants.command;

import dev.grants.GrantService.Target;
import dev.grants.GrantsPlugin;
import dev.grants.gui.HistoryMenu;
import dev.grants.gui.RankSelectMenu;
import dev.grants.gui.RemoveMenu;
import dev.grants.util.Items;
import dev.grants.util.Text;
import net.luckperms.api.model.group.Group;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

public final class GrantsCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = Collections.unmodifiableList(
            Arrays.asList("add", "remove", "history", "color", "reload"));
    /** Fixed on purpose: /grants info is not editable in any .yml file. */
    private static final List<String> INFO = Collections.unmodifiableList(Arrays.asList(
            "&b&lGrants Plugin",
            "",
            "- Made By: &b&lSnipst",
            "",
            "- Version: &b&l1.1.1"));

    private final GrantsPlugin plugin;

    public GrantsCommand(GrantsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("colour")) sub = "color";
        if (sub.equals("info")) {
            info(sender);
            return true;
        }
        // Anyone missing the permission for what they typed just sees the info text.
        String perm = SUBCOMMANDS.contains(sub) ? "grants." + sub : null;
        if (perm == null ? !hasAnyAccess(sender) : !sender.hasPermission(perm)) {
            info(sender);
            return true;
        }
        switch (sub) {
            case "add":
                withTarget(sender, label, sub, args, (p, t) ->
                        plugin.service().loadGroups().whenComplete((v, ex) -> plugin.service().refresh(t.uuid(), () -> {
                            if (!p.isOnline()) return;
                            if (plugin.service().grantableGroups().isEmpty()) {
                                plugin.send(p, "no-ranks");
                                return;
                            }
                            new RankSelectMenu(plugin, t).open(p);
                        })));
                break;
            case "remove":
                withTarget(sender, label, sub, args, (p, t) ->
                        plugin.service().refresh(t.uuid(), () -> {
                            if (!p.isOnline()) return;
                            if (plugin.store().active(t.uuid()).isEmpty()) {
                                plugin.send(p, "no-active-grants", "player", t.name());
                                return;
                            }
                            new RemoveMenu(plugin, t).open(p);
                        }));
                break;
            case "history":
                withTarget(sender, label, sub, args, (p, t) ->
                        plugin.service().refresh(t.uuid(), () -> {
                            if (p.isOnline()) new HistoryMenu(plugin, t).open(p);
                        }));
                break;
            case "color":
                color(sender, label, args);
                break;
            case "reload":
                plugin.reloadFiles();
                if (plugin.store().database() != null) plugin.pullColors(plugin.store().database());
                plugin.service().loadGroups();
                plugin.send(sender, "reloaded");
                break;
            default:
                help(sender, label);
        }
        return true;
    }

    /** Checks the argument, resolves the player name, then runs {@code action} on the main thread. */
    private void withTarget(CommandSender sender, String label, String sub, String[] args, BiConsumer<Player, Target> action) {
        if (!(sender instanceof Player)) {
            plugin.send(sender, "players-only");
            return;
        }
        final Player player = (Player) sender;
        if (args.length < 2) {
            plugin.send(player, "usage-player", "label", label, "command", sub);
            return;
        }
        final String name = args[1];
        plugin.service().resolve(name).whenComplete((target, ex) -> plugin.sync(() -> {
            if (!player.isOnline()) return;
            if (ex != null || target == null) {
                plugin.send(player, "player-not-found", "player", name);
                return;
            }
            action.accept(player, target);
        }));
    }

    private void color(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            plugin.send(sender, "usage-color", "label", label);
            return;
        }
        Group group = plugin.service().group(args[1]);
        if (group == null) {
            plugin.send(sender, "unknown-rank", "rank", args[1]);
            return;
        }
        String color = Items.parseColor(args[2]);
        if (color == null) {
            plugin.send(sender, "unknown-color", "color", args[2], "colors", String.join(", ", Items.colorNames()));
            return;
        }
        plugin.setRankColor(group.getName().toLowerCase(Locale.ROOT), color);
        plugin.send(sender, "color-set", "rank", group.getName(), "color", color);
    }

    private static boolean hasAnyAccess(CommandSender sender) {
        for (String s : SUBCOMMANDS) if (sender.hasPermission("grants." + s)) return true;
        return false;
    }

    private void info(CommandSender sender) {
        for (String line : INFO) sender.sendMessage(Text.color(line));
    }

    /** Help text comes from messages.yml -> help; each command's part only shows with its permission. */
    private void help(CommandSender sender, String label) {
        plugin.sendLines(sender, "help.header", "label", label);
        for (String sub : SUBCOMMANDS) {
            if (sender.hasPermission("grants." + sub)) plugin.sendLines(sender, "help." + sub, "label", label);
        }
        plugin.sendLines(sender, "help.footer", "label", label);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<String>();
        if (args.length == 1) {
            for (String s : SUBCOMMANDS) if (sender.hasPermission("grants." + s)) options.add(s);
            if (!options.isEmpty()) options.add("help");
            options.add("info");
        } else if (!hasAnyAccess(sender)) {
            return options;
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("add") || sub.equals("remove") || sub.equals("history")) {
                for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
            } else if (sub.equals("color") || sub.equals("colour")) {
                for (Group g : plugin.service().grantableGroups()) options.add(g.getName());
            }
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("color") || args[0].equalsIgnoreCase("colour"))) {
            options.addAll(Items.colorNames());
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return options;
    }
}
