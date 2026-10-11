package dev.grants.gui;

import dev.grants.GrantService.Target;
import dev.grants.GrantsPlugin;
import dev.grants.model.Grant;
import dev.grants.util.Items;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/** Cancel on the left, the grant in the middle, confirm on the right. Layout from gui.yml -> confirm-remove. */
public final class ConfirmRemoveMenu extends Menu {

    private final Target target;
    private final Grant grant;

    public ConfirmRemoveMenu(GrantsPlugin plugin, Target target, Grant grant) {
        super(plugin);
        this.target = target;
        this.grant = grant;
    }

    @Override
    protected java.util.UUID subject() {
        return grant.target();
    }

    /** If the grant was removed or expired elsewhere meanwhile, close instead of offering to remove it. */
    @Override
    protected void subjectChanged(Player viewer) {
        Grant fresh = plugin.store().get(grant.id());
        if (fresh == null || !fresh.isActive()) {
            viewer.closeInventory();
            plugin.send(viewer, "grant-not-active");
        }
    }

    @Override
    protected void build() {
        String[] ph = GrantItems.placeholders(plugin, grant);
        int size = rows("confirm-remove.rows", 3) * 9;
        create(size, text("confirm-remove.title", ph));

        String icon = Items.material(section("confirm-remove.info"), "BOOK");
        set(slot("confirm-remove.info.slot", 13, size), GrantItems.grant(plugin, grant, icon, Collections.<String>emptyList()));
        set(slot("confirm-remove.cancel.slot", 11, size), item("confirm-remove.cancel", "RED_CONCRETE", ph), p -> {
            if (plugin.store().active(target.uuid()).isEmpty()) p.closeInventory();
            else new RemoveMenu(plugin, target).open(p);
        });
        set(slot("confirm-remove.confirm.slot", 15, size), item("confirm-remove.confirm", "LIME_CONCRETE", ph), p -> {
            p.closeInventory();
            plugin.service().remove(p, grant);
        });
    }
}
