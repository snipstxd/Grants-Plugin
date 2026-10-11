package dev.grants.util;

import org.bukkit.Bukkit;

/** The running Minecraft version, e.g. 1.8.8 -> (1, 8), 1.21.11 -> (1, 21), 26.3 -> (26, 3). */
public final class Version {

    public static final int MAJOR;
    public static final int MINOR;

    static {
        int major = 1, minor = 21;
        try {
            // "1.8.8-R0.1-SNAPSHOT", "1.21.11-R0.1-SNAPSHOT", "26.3-R0.1-SNAPSHOT"
            String[] parts = Bukkit.getBukkitVersion().split("-")[0].split("\\.");
            major = Integer.parseInt(parts[0]);
            minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        } catch (RuntimeException ignored) {
            // Unknown format: assume modern.
        }
        MAJOR = major;
        MINOR = minor;
    }

    private Version() {}

    public static boolean atLeast(int major, int minor) {
        return MAJOR > major || (MAJOR == major && MINOR >= minor);
    }

    /** 1.8 - 1.12: WOOL + colour number instead of GREEN_WOOL etc. */
    public static boolean legacyMaterials() {
        return !atLeast(1, 13);
    }

    /** Hex colours (&#RRGGBB) only exist from 1.16. */
    public static boolean hexColors() {
        return atLeast(1, 16);
    }

    /** Sound names like "ui.button.click" exist from 1.9; 1.8 uses "random.click". */
    public static boolean modernSounds() {
        return atLeast(1, 9);
    }

    /** Before 1.13 menu titles over 32 characters crash the menu. */
    public static boolean longTitles() {
        return atLeast(1, 13);
    }

    public static String describe() {
        return MAJOR + "." + MINOR;
    }
}
