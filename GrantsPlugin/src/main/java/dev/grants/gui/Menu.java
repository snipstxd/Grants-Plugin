package dev.grants.gui;

import dev.grants.GrantsPlugin;
import dev.grants.util.Items;
import dev.grants.util.Text;
import dev.grants.util.Version;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** A chest GUI whose slots can carry click actions. Subclasses fill it in {@link #build()}. */
public abstract class Menu implements InventoryHolder {

    /** Paged menus show this many entries per page and use the bottom row for navigation. */
    protected static final int PAGE_SIZE = 45;
    protected static final int PREV_SLOT = 48, CLOSE_SLOT = 49, NEXT_SLOT = 50;

    protected final GrantsPlugin plugin;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();
    private Inventory inventory;
    protected int page;

    protected Menu(GrantsPlugin plugin) {
        this.plugin = plugin;
    }

    protected abstract void build();

    protected void create(int size, String title) {
        String t = Text.color(title);
        // Before 1.13 a title over 32 characters throws an error.
        if (!Version.longTitles() && t.length() > 32) {
            t = t.substring(0, 32);
            if (t.endsWith("\u00a7")) t = t.substring(0, 31);
        }
        inventory = Bukkit.createInventory(this, size, t);
        actions.clear();
    }

    protected void set(int slot, ItemStack item) {
        inventory.setItem(slot, item);
    }

    protected void set(int slot, ItemStack item, Consumer<Player> action) {
        inventory.setItem(slot, item);
        actions.put(slot, action);
    }

    /** Which menu each player has open (tracked here because InventoryView can't be used on every version). */
    private static final Map<java.util.UUID, Menu> OPEN = new HashMap<java.util.UUID, Menu>();

    public void open(Player player) {
        build();
        player.openInventory(inventory);
        OPEN.put(player.getUniqueId(), this);
    }

    /** The player whose grants this menu shows, if any; it is redrawn live when they change. */
    protected java.util.UUID subject() {
        return null;
    }

    /** Called when the subject's grants change on any server. Default: redraw. */
    protected void subjectChanged(Player viewer) {
        open(viewer);
    }

    static void closed(Player player, Inventory inventory) {
        Menu menu = OPEN.get(player.getUniqueId());
        if (menu != null && menu.inventory == inventory) OPEN.remove(player.getUniqueId());
    }

    static void quit(Player player) {
        OPEN.remove(player.getUniqueId());
    }

    public static boolean hasViewers(java.util.UUID subject) {
        for (Menu m : OPEN.values()) if (subject.equals(m.subject())) return true;
        return false;
    }

    public static void refreshViewers(java.util.UUID subject) {
        for (Map.Entry<java.util.UUID, Menu> e : new java.util.ArrayList<Map.Entry<java.util.UUID, Menu>>(OPEN.entrySet())) {
            if (!subject.equals(e.getValue().subject())) continue;
            Player viewer = Bukkit.getPlayer(e.getKey());
            if (viewer == null) OPEN.remove(e.getKey());
            else e.getValue().subjectChanged(viewer);
        }
    }

    void click(Player player, int slot) {
        Consumer<Player> action = actions.get(slot);
        if (action == null) return;
        plugin.sound(player, "click");
        action.accept(player);
    }

    // ---------------------------------------------------------------- gui.yml helpers

    protected ConfigurationSection section(String path) {
        return plugin.gui().getConfigurationSection(path);
    }

    protected String text(String path, String... placeholders) {
        return Text.apply(plugin.gui().getString(path, ""), placeholders);
    }

    protected ItemStack item(String path, String fallback, String... placeholders) {
        return Items.fromConfig(section(path), fallback, placeholders);
    }

    /** Slot from gui.yml, kept inside the menu. */
    protected int slot(String path, int fallback, int size) {
        int s = plugin.gui().getInt(path, fallback);
        return s >= 0 && s < size ? s : fallback;
    }

    protected int rows(String path, int fallback) {
        return Math.max(1, Math.min(6, plugin.gui().getInt(path, fallback)));
    }

    protected String noPrefix(String prefix) {
        return prefix == null || prefix.trim().isEmpty() ? plugin.gui().getString("no-prefix", "&8None") : prefix;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    // ---------------------------------------------------------------- layout helpers

    /** Smallest chest size (in slots) that fits {@code count} items, 9..54. */
    protected static int sizeFor(int count) {
        int rows = Math.max(1, (count + 8) / 9);
        return Math.min(6, rows) * 9;
    }

    protected static int pages(int count) {
        return Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** Previous / close / next buttons on the bottom row of a 54-slot paged menu. */
    protected void navigation(int count) {
        int total = pages(count);
        String pages = String.valueOf(total);
        if (page > 0) {
            set(PREV_SLOT, item("navigation.previous", "ARROW", "page", String.valueOf(page), "pages", pages),
                    p -> { page--; open(p); });
        }
        set(CLOSE_SLOT, item("navigation.close", "BARRIER"), Player::closeInventory);
        if (page < total - 1) {
            set(NEXT_SLOT, item("navigation.next", "ARROW", "page", String.valueOf(page + 2), "pages", pages),
                    p -> { page++; open(p); });
        }
    }

    /** Slots inside a bordered box (columns 1-7, starting on row 1) for {@code count} buttons. */
    protected static int[] boxSlots(int count) {
        int[] slots = new int[count];
        for (int i = 0; i < count; i++) slots[i] = 9 * (1 + i / 7) + 1 + i % 7;
        return slots;
    }

    /** Chest size for a box of {@code count} buttons plus a top row and a bottom row. */
    protected static int boxSize(int count) {
        int rows = Math.max(1, (count + 6) / 7) + 2;
        return Math.min(6, rows) * 9;
    }
}
