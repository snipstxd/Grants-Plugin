package dev.grants.util;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Plugin author / version, the /grants info text and the startup banner. None of it comes from a .yml file. */
public final class Credits {

    private static final byte[] A = {0x09, 0x34, 0x33, 0x2A, 0x29, 0x2E};

    private static final String[] LOGO = {
            "  ____                 _       ",
            " / ___|_ __ __ _ _ __ | |_ ___ ",
            "| |  _| '__/ _` | '_ \\| __/ __|",
            "| |_| | | | (_| | | | | |_\\__ \\",
            " \\____|_|  \\__,_|_| |_|\\__|___/",
    };

    private Credits() {}

    public static String author() {
        char[] c = new char[A.length];
        for (int i = 0; i < A.length; i++) c[i] = (char) (A[i] ^ 0x5A);
        return new String(c);
    }

    public static String version(JavaPlugin plugin) {
        return plugin.getDescription().getVersion();
    }

    /** False if plugin.yml no longer names the real author. */
    public static boolean intact(JavaPlugin plugin) {
        List<String> authors = plugin.getDescription().getAuthors();
        return authors != null && authors.size() == 1 && author().equals(authors.get(0));
    }

    /** The /grants info lines. */
    public static List<String> info(JavaPlugin plugin) {
        return Collections.unmodifiableList(Arrays.asList(
                "&b&lGrants Plugin",
                "",
                "- Made By: &b&l" + author(),
                "",
                "- Version: &b&l" + version(plugin)));
    }

    /** Green and white banner in the console on startup. */
    public static void banner(JavaPlugin plugin) {
        CommandSender console = Bukkit.getConsoleSender();
        String version = version(plugin);

        send(console, "");
        for (String line : LOGO) send(console, "&a" + line);
        send(console, "");
        send(console, "&a&lGrants Plugin &f- &a" + version);
        send(console, "");
        send(console, "&aAuthor: &f" + author());
        send(console, "&aVersion: &f" + version);
        send(console, "");
        send(console, "&aJava Version: &f" + System.getProperty("java.version"));
        send(console, "&aOS: &f" + System.getProperty("os.name"));
        send(console, "&aServer Version: &f" + Bukkit.getVersion());
        // Only shown when server.properties has a server-ip set.
        String ip = Bukkit.getIp();
        if (ip != null && !ip.trim().isEmpty()) send(console, "&aServer IP: &f" + ip.trim() + ":" + Bukkit.getPort());
        send(console, "");
    }

    private static void send(CommandSender to, String line) {
        to.sendMessage(Text.color(line));
    }
}
