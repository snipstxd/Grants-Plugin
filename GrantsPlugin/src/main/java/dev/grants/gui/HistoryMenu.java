package dev.grants.gui;

import dev.grants.GrantService.Target;
import dev.grants.GrantsPlugin;
import dev.grants.model.Grant;

import java.util.Collections;
import java.util.List;

/** /grants history: active (green) on the left, expired (red) after, default rank (grey) far right. */
public final class HistoryMenu extends Menu {

    private final Target target;

    public HistoryMenu(GrantsPlugin plugin, Target target) {
        super(plugin);
        this.target = target;
    }

    @Override
    protected java.util.UUID subject() {
        return target.uuid();
    }

    @Override
    protected void build() {
        List<Grant> grants = plugin.store().history(target.uuid());
        boolean paged = grants.size() + 1 > 54;
        int size = paged ? 54 : sizeFor(grants.size() + 1);
        create(size, text("history.title", "player", target.name()));

        int start = paged ? page * PAGE_SIZE : 0;
        int end = paged ? Math.min(grants.size(), start + PAGE_SIZE) : grants.size();
        for (int i = start; i < end; i++) {
            set(i - start, GrantItems.grant(plugin, grants.get(i), Collections.<String>emptyList()));
        }
        if (paged) navigation(grants.size());

        String def = plugin.settings().getString("default-group", "default");
        set(size - 1, item("history.default-rank", "GRAY_WOOL",
                "player", target.name(),
                "rank", plugin.service().displayName(def),
                "prefix", noPrefix(plugin.service().prefix(def))));
    }
}
