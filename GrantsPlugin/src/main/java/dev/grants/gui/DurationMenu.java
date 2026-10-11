package dev.grants.gui;

import dev.grants.GrantService.Target;
import dev.grants.GrantsPlugin;
import dev.grants.util.Durations;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Step 2 of /grants add: pick how long the rank lasts. */
public final class DurationMenu extends Menu {

    private final Target target;
    private final String group;

    public DurationMenu(GrantsPlugin plugin, Target target, String group) {
        super(plugin);
        this.target = target;
        this.group = group;
    }

    @Override
    protected void build() {
        List<Long> presets = new ArrayList<>();
        for (String s : plugin.durations().getStringList("presets")) {
            long d = Durations.parse(s);
            if (d > 0 && presets.size() < 26) presets.add(d);
        }
        int count = presets.size() + 2; // + permanent + custom
        int size = boxSize(count);
        int[] slots = boxSlots(count);
        String[] ph = {
                "player", target.name(),
                "rank", plugin.service().displayName(group),
                "prefix", plugin.service().prefixOrName(group)};
        create(size, text("duration.title", ph));

        set(slot("duration.header.slot", 4, size), item("duration.header", "NAME_TAG", ph));

        int i = 0;
        for (long d : presets) {
            set(slots[i++], item("duration.preset", "CLOCK", "duration", Durations.format(d)),
                    p -> next(p, d));
        }
        set(slots[i++], item("duration.permanent", "NETHER_STAR", "duration", Durations.format(0)),
                p -> next(p, Durations.PERMANENT));
        set(slots[i], item("duration.custom", "OAK_SIGN"), this::askCustom);

        set(size - 9, item("duration.back", "ARROW"), p -> new RankSelectMenu(plugin, target).open(p));
    }

    private void next(Player player, long duration) {
        new ReasonMenu(plugin, target, group, duration).open(player);
    }

    private void askCustom(Player player) {
        player.closeInventory();
        plugin.send(player, "enter-duration");
        awaitDuration(player);
    }

    private void awaitDuration(Player player) {
        plugin.chatInput().await(player, input -> {
            if (input.equalsIgnoreCase(plugin.cancelWord())) {
                open(player);
                return;
            }
            long d = Durations.parse(input);
            if (d == Durations.INVALID) {
                plugin.sound(player, "error");
                plugin.send(player, "invalid-duration", "input", input);
                awaitDuration(player);
                return;
            }
            next(player, d);
        });
    }
}
