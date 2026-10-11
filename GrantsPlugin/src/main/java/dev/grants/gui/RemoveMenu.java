package dev.grants.gui;

import dev.grants.GrantService.Target;
import dev.grants.GrantsPlugin;
import dev.grants.model.Grant;

import java.util.List;

/** /grants remove: list active grants; clicking one asks for confirmation. */
public final class RemoveMenu extends Menu {

    private final Target target;

    public RemoveMenu(GrantsPlugin plugin, Target target) {
        super(plugin);
        this.target = target;
    }

    @Override
    protected java.util.UUID subject() {
        return target.uuid();
    }

    @Override
    protected void build() {
        List<Grant> grants = plugin.store().active(target.uuid());
        boolean paged = grants.size() > 54;
        create(paged ? 54 : sizeFor(grants.size()), text("remove.title", "player", target.name()));

        List<String> extra = plugin.gui().getStringList("remove.extra-lore");
        int start = paged ? page * PAGE_SIZE : 0;
        int end = paged ? Math.min(grants.size(), start + PAGE_SIZE) : grants.size();
        for (int i = start; i < end; i++) {
            Grant grant = grants.get(i);
            set(i - start, GrantItems.grant(plugin, grant, extra), p -> {
                if (plugin.service().canManage(p, grant.group())) {
                    new ConfirmRemoveMenu(plugin, target, grant).open(p);
                } else {
                    plugin.sound(p, "error");
                    plugin.send(p, "remove-too-high");
                }
            });
        }
        if (paged) navigation(grants.size());
    }
}
