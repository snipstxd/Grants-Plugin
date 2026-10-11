package dev.grants.gui;

import dev.grants.GrantsPlugin;
import dev.grants.model.Grant;
import dev.grants.util.Items;
import dev.grants.util.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** The item that describes a single grant (history, remove and confirm menus). Look comes from gui.yml -> grant-item. */
final class GrantItems {

    private GrantItems() {}

    /** {placeholder} values for a grant, usable in any gui.yml text. */
    static String[] placeholders(GrantsPlugin plugin, Grant g) {
        YamlConfiguration gui = plugin.gui();
        String prefix = plugin.service().prefix(g.group());
        return new String[]{
                "rank", plugin.service().displayName(g.group()),
                "prefix", prefix.trim().isEmpty() ? gui.getString("no-prefix", "&8None") : prefix,
                "player", g.targetName(),
                "status", gui.getString(g.isActive() ? "grant-item.status-active" : "grant-item.status-expired", ""),
                "granted_by", g.grantedBy(),
                "granted_on", Text.date(g.grantedAt()),
                "expires_on", g.isPermanent() ? gui.getString("grant-item.never-expires", "Never") : Text.date(g.expiresAt()),
                "reason", g.reason(),
                "ended_on", g.endedAt() > 0 ? Text.date(g.endedAt()) : "",
                "removed_by", g.removedBy() == null ? "" : g.removedBy()};
    }

    static ItemStack grant(GrantsPlugin plugin, Grant g, List<String> extraLore) {
        return grant(plugin, g, null, extraLore);
    }

    /** {@code icon} replaces the green/red wool (null keeps it). */
    static ItemStack grant(GrantsPlugin plugin, Grant g, String icon, List<String> extraLore) {
        YamlConfiguration gui = plugin.gui();
        String[] ph = placeholders(plugin, g);

        List<String> lore = new ArrayList<String>();
        for (String line : gui.getStringList("grant-item.lore")) lore.add(Text.apply(line, ph));
        String extra = "";
        if (g.status() == Grant.Status.EXPIRED) extra = gui.getString("grant-item.expired-line", "");
        else if (g.status() == Grant.Status.REMOVED) extra = gui.getString("grant-item.removed-line", "");
        if (!extra.isEmpty()) lore.add(Text.apply(extra, ph));
        for (String line : extraLore) lore.add(Text.apply(line, ph));

        boolean active = g.isActive();
        ConfigurationSection look = gui.getConfigurationSection(active ? "grant-item.active" : "grant-item.expired");
        String material = icon != null ? icon : Items.material(look, active ? "GREEN_WOOL" : "RED_WOOL");
        String name = look == null ? "{rank}" : look.getString("name", "{rank}");
        return Items.of(material, Text.apply(name, ph), lore);
    }
}
