package dev.grants.gui;

import dev.grants.GrantService.Target;
import dev.grants.GrantsPlugin;
import dev.grants.util.Durations;
import org.bukkit.entity.Player;

import java.util.List;

/** Step 3 of /grants add: pick a reason. */
public final class ReasonMenu extends Menu {

    private final Target target;
    private final String group;
    private final long duration;

    public ReasonMenu(GrantsPlugin plugin, Target target, String group, long duration) {
        super(plugin);
        this.target = target;
        this.group = group;
        this.duration = duration;
    }

    @Override
    protected void build() {
        List<String> reasons = plugin.reasons().getStringList("reasons");
        if (reasons.size() > 27) reasons = reasons.subList(0, 27);
        int count = reasons.size() + 1; // + custom
        int size = boxSize(count);
        int[] slots = boxSlots(count);
        String[] ph = {
                "player", target.name(),
                "rank", plugin.service().displayName(group),
                "prefix", plugin.service().prefixOrName(group),
                "duration", Durations.format(duration)};
        create(size, text("reason.title", ph));

        set(slot("reason.header.slot", 4, size), item("reason.header", "NAME_TAG", ph));

        int i = 0;
        for (String reason : reasons) {
            set(slots[i++], item("reason.preset", "PAPER", "reason", reason), p -> finish(p, reason));
        }
        set(slots[i], item("reason.custom", "WRITABLE_BOOK"), this::askCustom);

        set(size - 9, item("reason.back", "ARROW"), p -> new DurationMenu(plugin, target, group).open(p));
    }

    private void finish(Player player, String reason) {
        new ConfirmGrantMenu(plugin, target, group, duration, reason).open(player);
    }

    private void askCustom(Player player) {
        player.closeInventory();
        plugin.send(player, "enter-reason");
        plugin.chatInput().await(player, input -> {
            if (input.equalsIgnoreCase(plugin.cancelWord()) || input.trim().isEmpty()) {
                open(player);
                return;
            }
            finish(player, input);
        });
    }
}
