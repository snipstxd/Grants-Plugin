package dev.grants.discord;

import dev.grants.GrantService;
import dev.grants.GrantService.Target;
import dev.grants.GrantsPlugin;
import dev.grants.model.Grant;
import dev.grants.util.Durations;
import dev.grants.util.Text;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.luckperms.api.model.group.Group;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Discord bot (JDA) offering /grants add, remove and history to members with an allowed role (discord.yml).
 * JDA events arrive on JDA's own threads; all plugin work is hopped to the main thread.
 */
public final class DiscordBot extends ListenerAdapter {

    private static final String COMMAND = "grants";
    private static final int EMBED_COLOR = 0x55FFFF;

    private final GrantsPlugin plugin;
    private volatile JDA jda;
    /** discord.yml -> discord-servers, by guild ID. */
    private volatile Map<String, DiscordServer> servers = Collections.emptyMap();
    private volatile boolean privateReplies;

    public DiscordBot(GrantsPlugin plugin) {
        this.plugin = plugin;
    }

    private YamlConfiguration config() {
        return plugin.discord();
    }

    private static final class DiscordServer {
        final String name;
        final Set<String> roles;

        DiscordServer(String name, Set<String> roles) {
            this.name = name;
            this.roles = roles;
        }
    }

    /** Re-reads the settings that apply without a restart. */
    public void reload() {
        Map<String, DiscordServer> found = new LinkedHashMap<String, DiscordServer>();
        ConfigurationSection section = config().getConfigurationSection("discord-servers");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection s = section.getConfigurationSection(key);
                String guildId = s == null ? "" : s.getString("guild-id", "").trim();
                if (guildId.isEmpty()) continue;
                Set<String> roles = new HashSet<String>();
                for (String id : s.getStringList("allowed-roles")) roles.add(id.trim());
                found.put(guildId, new DiscordServer(s.getString("name", key), roles));
            }
        }
        servers = found;
        privateReplies = config().getBoolean("private-replies", false);

        JDA current = jda;
        if (current != null && current.getStatus() == JDA.Status.CONNECTED) registerCommands(current);
    }

    /** Brings a discord.yml from an older version up to date, keeping the owner's IDs. */
    public static void migrate(GrantsPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "discord.yml");
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        boolean changed = false;
        // One Discord server only: guild-id and allowed-roles were at the top. They become discord-servers -> main.
        if (!yaml.isConfigurationSection("discord-servers") && yaml.isSet("allowed-roles")) {
            yaml.set("discord-servers.main.name", yaml.getString("log.discord-name", "Discord"));
            yaml.set("discord-servers.main.guild-id", yaml.getString("guild-id", ""));
            yaml.set("discord-servers.main.allowed-roles", yaml.getStringList("allowed-roles"));
            yaml.set("guild-id", null);
            yaml.set("allowed-roles", null);
            yaml.set("log.discord-name", null);
            changed = true;
        }
        // Each Discord server had its own log-channel-id. Now there is one; keep the first that was filled in.
        ConfigurationSection servers = yaml.getConfigurationSection("discord-servers");
        if (servers != null) {
            for (String key : servers.getKeys(false)) {
                if (!servers.isSet(key + ".log-channel-id")) continue;
                String id = servers.getString(key + ".log-channel-id", "").trim();
                if (!id.isEmpty() && yaml.getString("log-channel-id", "").trim().isEmpty()) yaml.set("log-channel-id", id);
                servers.set(key + ".log-channel-id", null);
                changed = true;
            }
        }
        if (!changed) return;
        try {
            yaml.save(file);
            plugin.getLogger().info("Updated discord.yml to the new layout (your token and IDs were kept).");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not update discord.yml", e);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    public void start() {
        reload();
        if (!config().getBoolean("enabled", false)) return;
        final String token = config().getString("token", "").trim();
        if (token.isEmpty()) {
            plugin.getLogger().warning("Discord bot is enabled but discord.yml has no token - not starting it.");
            return;
        }
        // Logging in talks to Discord, so keep it off the main thread.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                jda = JDABuilder.createLight(token).addEventListeners(this).build();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Could not start the Discord bot (is the token in discord.yml right?)", e);
            }
        });
    }

    public void stop() {
        JDA current = jda;
        jda = null;
        if (current == null) return;
        current.shutdownNow();
        try {
            current.awaitShutdown(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onReady(ReadyEvent event) {
        plugin.getLogger().info("Discord bot logged in as " + event.getJDA().getSelfUser().getName());
        registerCommands(event.getJDA());
    }

    /** Puts /grants on every configured Discord server and takes it off any other server the bot is in. */
    private void registerCommands(JDA jda) {
        Map<String, DiscordServer> current = servers;
        if (current.isEmpty()) {
            plugin.getLogger().warning("No Discord servers set up in discord.yml -> discord-servers (guild-id is empty) "
                    + "- the Discord commands won't show anywhere.");
        }
        // Older versions could register the command globally; clear that so it doesn't show twice.
        jda.updateCommands().queue();
        for (Guild guild : jda.getGuilds()) {
            if (current.containsKey(guild.getId())) guild.updateCommands().addCommands(command()).queue();
            else guild.updateCommands().queue();
        }
        for (Map.Entry<String, DiscordServer> e : current.entrySet()) {
            if (jda.getGuildById(e.getKey()) == null) {
                plugin.getLogger().warning("The Discord bot isn't in " + e.getValue().name + " (guild-id " + e.getKey()
                        + ") - invite it there first.");
            }
        }
    }

    private static SlashCommandData command() {
        return Commands.slash(COMMAND, "Grant, remove and view ranks")
                .setContexts(InteractionContextType.GUILD)
                .addSubcommands(
                        new SubcommandData("add", "Give a player a rank").addOptions(
                                new OptionData(OptionType.STRING, "player", "Minecraft name", true, true),
                                new OptionData(OptionType.STRING, "rank", "LuckPerms rank", true, true),
                                new OptionData(OptionType.STRING, "duration", "e.g. 30m, 2h, 7d, 1d12h or permanent", true, true),
                                new OptionData(OptionType.STRING, "reason", "Why they get the rank", true, true)),
                        new SubcommandData("remove", "Take a rank away from a player").addOptions(
                                new OptionData(OptionType.STRING, "player", "Minecraft name", true, true),
                                new OptionData(OptionType.STRING, "rank", "LuckPerms rank", true, true)),
                        new SubcommandData("history", "Show a player's grants").addOptions(
                                new OptionData(OptionType.STRING, "player", "Minecraft name", true, true)));
    }

    // ---------------------------------------------------------------- commands

    /** The member's Discord server if it's in discord.yml and they have one of its allowed roles, otherwise null. */
    private DiscordServer allowed(Member member) {
        if (member == null) return null;
        DiscordServer server = servers.get(member.getGuild().getId());
        if (server == null) return null;
        for (Role role : member.getRoles()) if (server.roles.contains(role.getId())) return server;
        return null;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals(COMMAND) || event.getSubcommandName() == null) return;
        DiscordServer server = allowed(event.getMember());
        if (server == null) {
            event.reply(discordMessage("no-permission")).setEphemeral(true).queue();
            return;
        }
        event.deferReply(privateReplies).queue();

        final InteractionHook hook = event.getHook();
        final String sub = event.getSubcommandName();
        final String player = option(event, "player");
        final String rank = option(event, "rank");
        final String duration = option(event, "duration");
        final String reason = option(event, "reason");
        final String actorName = Text.apply(config().getString("actor-name", "{user} (Discord)"),
                "user", event.getUser().getName(), "name", event.getMember().getEffectiveName());
        final GrantService.Actor actor = new DiscordActor(actorName, server.name, hook);

        plugin.sync(() -> withTarget(player, actor, target -> {
            switch (sub) {
                case "add":
                    add(actor, target, rank, duration, reason);
                    break;
                case "remove":
                    remove(actor, hook, target, rank);
                    break;
                case "history":
                    history(hook, target);
                    break;
                default:
            }
        }));
    }

    private static String option(SlashCommandInteractionEvent event, String name) {
        OptionMapping o = event.getOption(name);
        return o == null ? "" : o.getAsString().trim();
    }

    /** Resolves the player name, refreshes their grants, then runs {@code action} on the main thread. */
    private void withTarget(final String name, final GrantService.Actor actor, final java.util.function.Consumer<Target> action) {
        plugin.service().resolve(name).whenComplete((target, ex) -> plugin.sync(() -> {
            if (ex != null || target == null) {
                actor.reply(false, "player-not-found", "player", name);
                return;
            }
            plugin.service().refresh(target.uuid(), () -> action.accept(target));
        }));
    }

    private void add(GrantService.Actor actor, Target target, String rank, String durationText, String reason) {
        Group group = grantableGroup(rank);
        if (group == null) {
            actor.reply(false, "unknown-rank", "rank", rank);
            return;
        }
        long duration = Durations.parse(durationText);
        if (duration == Durations.INVALID) {
            ((DiscordActor) actor).say(discordMessage("invalid-duration", "input", durationText));
            return;
        }
        plugin.service().grant(actor, target, group.getName(), duration, reason);
    }

    private void remove(GrantService.Actor actor, InteractionHook hook, Target target, String rank) {
        List<Grant> grants = new ArrayList<Grant>();
        for (Grant g : plugin.store().active(target.uuid())) {
            if (g.group().equalsIgnoreCase(rank)) grants.add(g);
        }
        if (grants.isEmpty()) {
            hook.editOriginal(discordMessage("not-granted", "player", target.name(), "rank", rank)).queue();
            return;
        }
        plugin.service().remove(actor, grants);
    }

    private void history(InteractionHook hook, Target target) {
        showHistory(hook, target, MENU, 0);
    }

    // ---------------------------------------------------------------- history pages

    private static final String HISTORY_BUTTON = "grants-history";
    private static final String MENU = "menu";
    /** "expired" covers every grant that has ended: timed out or removed. */
    private static final List<String> CATEGORIES = Collections.unmodifiableList(java.util.Arrays.asList("active", "expired"));

    /** Replaces the hook's message with the category buttons (MENU) or one page of a category. */
    private void showHistory(InteractionHook hook, Target target, String category, int page) {
        List<Grant> all = plugin.store().history(target.uuid());
        String player = MarkdownSanitizer.escape(target.name());

        if (category.equals(MENU)) {
            List<Button> buttons = new ArrayList<Button>();
            for (String c : CATEGORIES) {
                buttons.add(Button.primary(historyButton(target, c, 0),
                        history(c + "-button", "count", String.valueOf(inCategory(all, c).size()))));
            }
            EmbedBuilder embed = new EmbedBuilder()
                    .setColor(color(history("menu-color")))
                    .setTitle(history("menu-title", "player", player))
                    .setDescription(history("menu-text", "player", player));
            hook.editOriginalEmbeds(embed.build()).setComponents(ActionRow.of(buttons)).queue();
            return;
        }

        List<Grant> grants = inCategory(all, category);
        int perPage = Math.max(1, Math.min(15, config().getInt("history.per-page", 8)));
        int pages = Math.max(1, (grants.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));

        StringBuilder text = new StringBuilder();
        if (grants.isEmpty()) text.append(history("empty", "player", player)).append("\n\n");
        for (Grant g : grants.subList(page * perPage, Math.min(grants.size(), (page + 1) * perPage))) {
            text.append(entry(g, player)).append("\n\n");
        }
        String footer = history("page", "page", String.valueOf(page + 1), "pages", String.valueOf(pages));
        // Embed descriptions hold up to 4096 characters.
        if (text.length() + footer.length() > 4096) text.setLength(4090 - footer.length());
        text.append(footer);

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(color(history(category + "-color")))
                .setTitle(history(category + "-title", "player", player))
                .setDescription(text.toString());
        hook.editOriginalEmbeds(embed.build()).setComponents(ActionRow.of(
                Button.primary(historyButton(target, category, page - 1), history("previous")).withDisabled(page == 0),
                Button.primary(historyButton(target, category, page + 1), history("next")).withDisabled(page >= pages - 1),
                Button.secondary(historyButton(target, MENU, 0), history("back")))).queue();
    }

    private String entry(Grant g, String player) {
        String reason = g.reason() == null ? "" : g.reason();
        if (reason.length() > 200) reason = reason.substring(0, 197) + "...";
        return history(g.status().name().toLowerCase(Locale.ROOT) + "-entry",
                "player", player,
                "rank", MarkdownSanitizer.escape(plugin.service().displayName(g.group())),
                "by", MarkdownSanitizer.escape(g.grantedBy()),
                "removed-by", MarkdownSanitizer.escape(g.removedBy() == null ? "" : g.removedBy()),
                "reason", MarkdownSanitizer.escape(reason),
                "duration", Durations.format(g.isPermanent() ? 0 : g.expiresAt() - g.grantedAt()),
                "timestamp", "<t:" + g.grantedAt() / 1000L + ":F>",
                "ended", "<t:" + g.endedAt() / 1000L + ":F>",
                "expires", g.isPermanent() ? history("never") : "<t:" + g.expiresAt() / 1000L + ":R>");
    }

    private static List<Grant> inCategory(List<Grant> grants, String category) {
        List<Grant> out = new ArrayList<Grant>();
        for (Grant g : grants) if (g.isActive() == category.equals("active")) out.add(g);
        return out;
    }

    /** Button IDs carry everything needed to redraw: grants-history|category|page|uuid|name (well under Discord's 100). */
    private static String historyButton(Target target, String category, int page) {
        return HISTORY_BUTTON + "|" + category + "|" + page + "|" + target.uuid() + "|" + target.name();
    }

    private String history(String key, String... placeholders) {
        return Text.apply(config().getString("history." + key, key), placeholders);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String[] parts = event.getComponentId().split("\\|", 5);
        if (parts.length != 5 || !parts[0].equals(HISTORY_BUTTON)) return;
        if (allowed(event.getMember()) == null) {
            event.reply(discordMessage("no-permission")).setEphemeral(true).queue();
            return;
        }
        final String category = parts[1];
        final int page;
        final Target target;
        try {
            page = Integer.parseInt(parts[2]);
            target = new Target(UUID.fromString(parts[3]), parts[4]);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!category.equals(MENU) && !CATEGORIES.contains(category)) return;
        event.deferEdit().queue();
        final InteractionHook hook = event.getHook();
        plugin.sync(() -> plugin.service().refresh(target.uuid(), () -> showHistory(hook, target, category, page)));
    }

    private Group grantableGroup(String name) {
        for (Group g : plugin.service().grantableGroups()) if (g.getName().equalsIgnoreCase(name)) return g;
        return null;
    }

    // ---------------------------------------------------------------- autocomplete

    @Override
    public void onCommandAutoCompleteInteraction(final CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals(COMMAND) || allowed(event.getMember()) == null) {
            event.replyChoices(Collections.<Command.Choice>emptyList()).queue();
            return;
        }
        final String option = event.getFocusedOption().getName();
        final String typed = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
        if (option.equals("rank") && "remove".equals(event.getSubcommandName())) {
            suggestActiveRanks(event, typed);
            return;
        }
        plugin.sync(() -> {
            List<String> options = new ArrayList<String>();
            switch (option) {
                case "player":
                    for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
                    break;
                case "rank":
                    for (Group g : plugin.service().grantableGroups()) options.add(g.getName());
                    break;
                case "duration":
                    options.addAll(plugin.durations().getStringList("presets"));
                    options.add("permanent");
                    break;
                case "reason":
                    options.addAll(plugin.reasons().getStringList("reasons"));
                    break;
                default:
            }
            event.replyChoices(choices(options, typed)).queue();
        });
    }

    /** For /grants remove: only the ranks the chosen player has an active grant for. */
    private void suggestActiveRanks(final CommandAutoCompleteInteractionEvent event, final String typed) {
        OptionMapping player = event.getOption("player");
        final String name = player == null ? "" : player.getAsString().trim();
        final AtomicBoolean answered = new AtomicBoolean();
        final java.util.function.Consumer<List<String>> answer = options -> {
            if (answered.compareAndSet(false, true)) event.replyChoices(choices(options, typed)).queue();
        };
        if (name.isEmpty()) {
            answer.accept(Collections.<String>emptyList());
            return;
        }
        plugin.sync(() -> {
            // Discord gives up on suggestions after 3 seconds; answer with nothing rather than not at all.
            Bukkit.getScheduler().runTaskLater(plugin, () -> answer.accept(Collections.<String>emptyList()), 50L);
            plugin.service().resolve(name).whenComplete((target, ex) -> plugin.sync(() -> {
                if (ex != null || target == null) {
                    answer.accept(Collections.<String>emptyList());
                    return;
                }
                plugin.service().refresh(target.uuid(), () -> {
                    Set<String> ranks = new LinkedHashSet<String>();
                    for (Grant g : plugin.store().active(target.uuid())) ranks.add(g.group());
                    answer.accept(new ArrayList<String>(ranks));
                });
            }));
        });
    }

    private static List<Command.Choice> choices(List<String> options, String typed) {
        return options.stream()
                .filter(o -> !o.isEmpty() && o.length() <= 100 && o.toLowerCase(Locale.ROOT).startsWith(typed))
                .limit(25)
                .map(o -> new Command.Choice(o, o))
                .collect(Collectors.toList());
    }

    // ---------------------------------------------------------------- grant log

    /** Posts a new grant to log-channel-id. {@code fromLuckPerms} = given with /lp rather than through Grants. */
    public void logGrant(Grant grant, boolean fromLuckPerms, String discordServer) {
        if (fromLuckPerms && !config().getBoolean("log.luckperms-commands", true)) return;
        EmbedBuilder embed = logEmbed("granted", grant, discordServer)
                .addField(log("duration"), Durations.format(grant.isPermanent() ? 0 : grant.expiresAt() - grant.grantedAt()), true)
                .addField(log("expires"), grant.isPermanent() ? log("never") : "<t:" + grant.expiresAt() / 1000L + ":R>", true)
                .addField(log("reason"), field(grant.reason()), false)
                .addField(log("granted-by"), field(grant.grantedBy()), true);
        post(embed);
    }

    /** Posts a removal to log-channel-id; all grants are for the same player and rank. */
    public void logRemoval(List<Grant> grants, String removedBy, String discordServer) {
        Grant first = grants.get(0);
        EmbedBuilder embed = logEmbed("removed", first, discordServer)
                .addField(log("granted-by"), field(first.grantedBy()), true)
                .addField(log("removed-by"), field(removedBy), true);
        post(embed);
    }

    /** The footer says where it was done: the Discord server's name, or this Minecraft server's. */
    private EmbedBuilder logEmbed(String type, Grant grant, String discordServer) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(log(type + "-title"))
                .setColor(color(config().getString("log." + type + "-color", "")))
                .addField(log("player"), field(grant.targetName()), true)
                .addField(log("rank"), field(plugin.service().displayName(grant.group())), true)
                .setTimestamp(java.time.Instant.now());
        String source = discordServer != null ? discordServer : serverName();
        if (!source.trim().isEmpty()) embed.setFooter(source.trim());
        return embed;
    }

    /** LuckPerms' server name when use-luckperms-server-name is on and LuckPerms has one, otherwise log.server-name. */
    private String serverName() {
        if (config().getBoolean("log.use-luckperms-server-name", true)) {
            String lp = plugin.service().luckPermsServerName();
            if (lp != null && !lp.trim().isEmpty() && !lp.equalsIgnoreCase("global")) return lp;
        }
        return config().getString("log.server-name", "");
    }

    private void post(EmbedBuilder embed) {
        JDA current = jda;
        String id = config().getString("log-channel-id", "").trim();
        if (current == null || id.isEmpty() || current.getStatus() != JDA.Status.CONNECTED) return;
        GuildMessageChannel channel;
        try {
            channel = current.getChannelById(GuildMessageChannel.class, id);
        } catch (IllegalArgumentException e) {
            channel = null;
        }
        if (channel == null) {
            plugin.getLogger().warning("Discord log channel " + id + " not found (check log-channel-id in discord.yml).");
            return;
        }
        channel.sendMessageEmbeds(embed.build()).queue(null, e ->
                plugin.getLogger().warning("Could not post to the Discord log channel: " + e.getMessage()));
    }

    private String log(String key) {
        return config().getString("log." + key, key);
    }

    /** Embed field values can't be empty and hold at most 1024 characters. */
    private static String field(String value) {
        if (value == null || value.trim().isEmpty()) return "-";
        String escaped = MarkdownSanitizer.escape(ChatColor.stripColor(Text.color(value)));
        return escaped.length() > 1024 ? escaped.substring(0, 1021) + "..." : escaped;
    }

    private static int color(String hex) {
        try {
            return Integer.parseInt(hex.trim().replace("#", ""), 16);
        } catch (NumberFormatException e) {
            return EMBED_COLOR;
        }
    }

    // ---------------------------------------------------------------- replies

    private String discordMessage(String key, String... placeholders) {
        return Text.apply(config().getString("messages." + key, key), placeholders);
    }

    /** messages.yml text as plain Discord text: no prefix, no colour codes, names not turned into markdown. */
    private String plain(String key, String... placeholders) {
        String msg = Text.apply(plugin.messages().getString(key, ""), placeholders);
        msg = Text.apply(msg, "cancel", plugin.cancelWord());
        return MarkdownSanitizer.escape(ChatColor.stripColor(Text.color(msg)).trim());
    }

    private final class DiscordActor implements GrantService.Actor {
        private final String name;
        private final String server;
        private final InteractionHook hook;

        DiscordActor(String name, String server, InteractionHook hook) {
            this.name = name;
            this.server = server;
            this.hook = hook;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String discordServer() {
            return server;
        }

        /** Access is controlled by allowed-roles, so the in-game rank hierarchy doesn't apply. */
        @Override
        public boolean canManage(String group) {
            return true;
        }

        @Override
        public void reply(boolean success, String key, String... placeholders) {
            String text = plain(key, placeholders);
            if (text.isEmpty()) text = success ? "Done." : "That didn't work.";
            say(text);
        }

        void say(String text) {
            hook.editOriginal(text).queue();
        }
    }
}
