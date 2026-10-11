package dev.grants.util;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds items by modern name ("GREEN_WOOL", "LIME_CONCRETE", "CLOCK") on every version from 1.8 up.
 * On 1.8 - 1.12 those names are translated to the old ones ("WOOL" + colour number, "WATCH", ...).
 * Material constants are never referenced directly, since most of them don't exist on every version.
 */
public final class Items {

    /** The 16 colours in their old 1.8 - 1.12 numeric order. */
    private static final List<String> COLORS = Arrays.asList(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    private static final Map<String, String> COLOR_ALIASES = new LinkedHashMap<String, String>();
    /** Modern name -> old name (optionally "NAME:data") for items that were renamed in 1.13. */
    private static final Map<String, String> LEGACY_NAMES = new HashMap<String, String>();
    /** Names that changed again after 1.13 (1.13 name on the right). */
    private static final Map<String, String> RENAMED_1_14 = new HashMap<String, String>();

    static {
        for (String c : COLORS) COLOR_ALIASES.put(c, c);
        COLOR_ALIASES.put("grey", "gray");
        COLOR_ALIASES.put("light_grey", "light_gray");
        COLOR_ALIASES.put("silver", "light_gray");
        COLOR_ALIASES.put("aqua", "light_blue");
        COLOR_ALIASES.put("dark_green", "green");
        COLOR_ALIASES.put("gold", "orange");
        COLOR_ALIASES.put("dark_purple", "purple");
        COLOR_ALIASES.put("dark_aqua", "cyan");

        RENAMED_1_14.put("OAK_SIGN", "SIGN");
        RENAMED_1_14.put("RED_DYE", "ROSE_RED");
        RENAMED_1_14.put("GREEN_DYE", "CACTUS_GREEN");
        RENAMED_1_14.put("YELLOW_DYE", "DANDELION_YELLOW");

        LEGACY_NAMES.put("CLOCK", "WATCH");
        LEGACY_NAMES.put("OAK_SIGN", "SIGN");
        LEGACY_NAMES.put("WRITABLE_BOOK", "BOOK_AND_QUILL");
        LEGACY_NAMES.put("PLAYER_HEAD", "SKULL_ITEM:3");
        LEGACY_NAMES.put("SKELETON_SKULL", "SKULL_ITEM:0");
        LEGACY_NAMES.put("EXPERIENCE_BOTTLE", "EXP_BOTTLE");
        LEGACY_NAMES.put("FIREWORK_ROCKET", "FIREWORK");
        LEGACY_NAMES.put("OAK_PLANKS", "WOOD");
        LEGACY_NAMES.put("CRAFTING_TABLE", "WORKBENCH");
        LEGACY_NAMES.put("COMPARATOR", "REDSTONE_COMPARATOR");
        LEGACY_NAMES.put("REPEATER", "DIODE");
        LEGACY_NAMES.put("LEAD", "LEASH");
        LEGACY_NAMES.put("GUNPOWDER", "SULPHUR");
        LEGACY_NAMES.put("FILLED_MAP", "MAP");
        LEGACY_NAMES.put("GOLDEN_SWORD", "GOLD_SWORD");
        LEGACY_NAMES.put("ENDER_EYE", "EYE_OF_ENDER");
        LEGACY_NAMES.put("SNOWBALL", "SNOW_BALL");
        LEGACY_NAMES.put("TERRACOTTA", "HARD_CLAY");
    }

    private static Method isItem;
    private static boolean isItemLooked;

    private Items() {}

    // ---------------------------------------------------------------- building

    public static ItemStack of(String material, String name, List<String> lore) {
        return named(stack(material, "STONE"), name, lore);
    }

    /** Sets the (colour-coded) name and lore on an existing item. */
    public static ItemStack named(ItemStack item, String name, List<String> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color(name));
            List<String> colored = new ArrayList<String>(lore.size());
            for (String line : lore) colored.add(Text.color(line));
            meta.setLore(colored);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack of(String material, String name, String... lore) {
        return of(material, name, Arrays.asList(lore));
    }

    /**
     * Builds an item from a gui.yml section (material / name / lore), filling in {placeholders}.
     * Falls back to {@code fallback} if the material is missing or doesn't exist on this version.
     */
    public static ItemStack fromConfig(ConfigurationSection section, String fallback, String... placeholders) {
        return fromConfig(section, fallback, Collections.<String>emptyList(), placeholders);
    }

    /** Same, with extra lore lines appended (already filled in). */
    public static ItemStack fromConfig(ConfigurationSection section, String fallback, List<String> extraLore,
                                       String... placeholders) {
        String name = section == null ? "" : section.getString("name", "");
        List<String> lore = new ArrayList<String>();
        if (section != null) {
            for (String line : section.getStringList("lore")) lore.add(Text.apply(line, placeholders));
        }
        lore.addAll(extraLore);
        return of(material(section, fallback), Text.apply(name, placeholders), lore);
    }

    /** The section's material if it exists on this server, otherwise {@code fallback}. */
    public static String material(ConfigurationSection section, String fallback) {
        if (section == null) return fallback;
        String m = section.getString("material", "");
        return resolve(m) != null ? m : fallback;
    }

    /** Item for {@code name}, or {@code fallback}, or stone. */
    public static ItemStack stack(String name, String fallback) {
        ItemStack s = resolve(name);
        if (s == null) s = resolve(fallback);
        if (s == null) s = new ItemStack(Material.STONE);
        return s;
    }

    /** Wool in one of the 16 colours ("red", "light_blue", ...). */
    public static ItemStack wool(String color) {
        String c = parseColor(color);
        return stack((c == null ? "white" : c).toUpperCase(Locale.ROOT) + "_WOOL", "WHITE_WOOL");
    }

    private static ItemStack resolve(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String name = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (Version.legacyMaterials()) return resolveLegacy(name);

        Material m = Material.matchMaterial(name);
        if (m == null && RENAMED_1_14.containsKey(name)) m = Material.matchMaterial(RENAMED_1_14.get(name));
        if (m == null || !isItem(m)) return null;
        return new ItemStack(m);
    }

    @SuppressWarnings("deprecation")
    private static ItemStack resolveLegacy(String name) {
        String base = name;
        int data = 0;

        if (LEGACY_NAMES.containsKey(name)) base = LEGACY_NAMES.get(name);

        String color = colorPrefix(name);
        if (color != null) {
            int idx = COLORS.indexOf(color);
            String rest = name.substring(color.length() + 1);
            if (rest.equals("WOOL")) { base = "WOOL"; data = idx; }
            else if (rest.equals("CONCRETE")) { base = Version.atLeast(1, 12) ? "CONCRETE" : "STAINED_CLAY"; data = idx; }
            else if (rest.equals("TERRACOTTA")) { base = "STAINED_CLAY"; data = idx; }
            else if (rest.equals("STAINED_GLASS")) { base = "STAINED_GLASS"; data = idx; }
            else if (rest.equals("STAINED_GLASS_PANE")) { base = "STAINED_GLASS_PANE"; data = idx; }
            else if (rest.equals("CARPET")) { base = "CARPET"; data = idx; }
            else if (rest.equals("DYE")) { base = "INK_SACK"; data = 15 - idx; }
        }

        int colon = base.indexOf(':');
        if (colon > 0) {
            try {
                data = Integer.parseInt(base.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                // keep 0
            }
            base = base.substring(0, colon);
        }
        Material m = Material.matchMaterial(base);
        if (m == null) return null;
        return new ItemStack(m, 1, (short) data);
    }

    /** "LIGHT_BLUE_WOOL" -> "light_blue" (longest colour first so LIGHT_BLUE isn't read as BLUE). */
    private static String colorPrefix(String name) {
        String best = null;
        for (String c : COLORS) {
            if (name.startsWith(c.toUpperCase(Locale.ROOT) + "_") && (best == null || c.length() > best.length())) best = c;
        }
        return best;
    }

    /** Material#isItem only exists from 1.13, so it's looked up by reflection. */
    private static boolean isItem(Material m) {
        if (!isItemLooked) {
            isItemLooked = true;
            try {
                isItem = Material.class.getMethod("isItem");
            } catch (NoSuchMethodException ignored) {
                isItem = null;
            }
        }
        if (isItem == null) return true;
        try {
            return (Boolean) isItem.invoke(m);
        } catch (Exception e) {
            return true;
        }
    }

    // ---------------------------------------------------------------- colours

    /** "red", "light_blue", "aqua", "grey"... -> canonical colour name, or null. */
    public static String parseColor(String name) {
        if (name == null) return null;
        return COLOR_ALIASES.get(name.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
    }

    public static List<String> colorNames() {
        return new ArrayList<String>(COLOR_ALIASES.keySet());
    }
}
