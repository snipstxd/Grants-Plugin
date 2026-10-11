package dev.grants.gui;

import dev.grants.GrantService.Target;
import dev.grants.GrantsPlugin;
import dev.grants.util.Items;
import dev.grants.util.Text;
import net.luckperms.api.model.group.Group;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Step 1 of /grants add: pick a rank. */
public final class RankSelectMenu extends Menu {

    private final Target target;
    private Player viewer;

    public RankSelectMenu(GrantsPlugin plugin, Target target) {
        super(plugin);
        this.target = target;
    }

    @Override
    public void open(Player player) {
        viewer = player;
        super.open(player);
    }

    @Override
    protected void build() {
        List<Group> groups = plugin.service().grantableGroups();
        boolean paged = groups.size() > 54;
        create(paged ? 54 : sizeFor(groups.size()), text("rank-select.title", "player", target.name()));

        int start = paged ? page * PAGE_SIZE : 0;
        int end = paged ? Math.min(groups.size(), start + PAGE_SIZE) : groups.size();
        for (int i = start; i < end; i++) {
            Group group = groups.get(i);
            String name = group.getName();
            boolean allowed = plugin.service().canManage(viewer, name);
            boolean owned = false;
            for (dev.grants.model.Grant g : plugin.store().active(target.uuid())) {
                if (g.group().equalsIgnoreCase(name)) owned = true;
            }
            String action = text(owned ? "rank-select.action-owned"
                    : allowed ? "rank-select.action-allowed" : "rank-select.action-locked");
            final String[] ph = {
                    "player", target.name(),
                    "rank", plugin.service().displayName(name),
                    "prefix", noPrefix(plugin.service().prefix(name)),
                    "weight", String.valueOf(group.getWeight().orElse(0)),
                    "action", action};
            set(i - start, Items.named(woolFor(name), Text.apply(text("rank-select.rank.name"), ph),
                            applyAll(section("rank-select.rank").getStringList("lore"), ph)),
                    p -> {
                        if (plugin.service().canManage(p, name)) {
                            plugin.service().checkAlreadyHas(target, name, (has, colored) -> {
                                if (!p.isOnline()) return;
                                if (has) plugin.service().sayAlreadyHas(p, colored, name);
                                else new DurationMenu(plugin, target, name).open(p);
                            });
                        } else {
                            plugin.sound(p, "error");
                            plugin.send(p, "rank-too-high");
                        }
                    });
        }
        if (paged) navigation(groups.size());
    }

    private static List<String> applyAll(List<String> lines, String[] ph) {
        List<String> out = new ArrayList<String>();
        for (String l : lines) out.add(Text.apply(l, ph));
        return out;
    }

    private ItemStack woolFor(String group) {
        return Items.wool(plugin.ranks().getString("colors." + group.toLowerCase(Locale.ROOT)));
    }
}
