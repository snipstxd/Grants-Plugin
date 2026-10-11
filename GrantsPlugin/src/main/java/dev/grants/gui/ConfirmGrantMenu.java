package dev.grants.gui;

import dev.grants.GrantService.Target;
import dev.grants.GrantsPlugin;
import dev.grants.util.Durations;

/** Last step of /grants add: cancel, a summary item, confirm. Layout from gui.yml -> confirm-grant. */
public final class ConfirmGrantMenu extends Menu {

    private final Target target;
    private final String group;
    private final long duration;
    private final String reason;

    public ConfirmGrantMenu(GrantsPlugin plugin, Target target, String group, long duration, String reason) {
        super(plugin);
        this.target = target;
        this.group = group;
        this.duration = duration;
        this.reason = reason;
    }

    @Override
    protected void build() {
        String[] ph = {
                "player", target.name(),
                "rank", plugin.service().displayName(group),
                "prefix", noPrefix(plugin.service().prefix(group)),
                "duration", Durations.format(duration),
                "reason", reason};
        int size = rows("confirm-grant.rows", 3) * 9;
        create(size, text("confirm-grant.title", ph));

        set(slot("confirm-grant.info.slot", 13, size), item("confirm-grant.info", "BOOK", ph));
        set(slot("confirm-grant.cancel.slot", 11, size), item("confirm-grant.cancel", "RED_CONCRETE", ph),
                p -> new ReasonMenu(plugin, target, group, duration).open(p));
        set(slot("confirm-grant.confirm.slot", 15, size), item("confirm-grant.confirm", "LIME_CONCRETE", ph), p -> {
            p.closeInventory();
            plugin.service().grant(p, target, group, duration, reason);
        });
    }
}
