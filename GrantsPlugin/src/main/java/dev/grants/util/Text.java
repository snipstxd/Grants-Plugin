package dev.grants.util;

import org.bukkit.ChatColor;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Text {

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM yyyy, hh:mm a z");

    private Text() {}

    public static void configureDates(String pattern, String timezone) {
        SimpleDateFormat f;
        try {
            f = new SimpleDateFormat(pattern);
        } catch (IllegalArgumentException e) {
            f = new SimpleDateFormat("dd MMM yyyy, hh:mm a z");
        }
        if (timezone != null && !timezone.trim().isEmpty()) f.setTimeZone(TimeZone.getTimeZone(timezone));
        dateFormat = f;
    }

    public static String date(long millis) {
        return dateFormat.format(new Date(millis));
    }

    private static final Pattern TRAILING = Pattern.compile("(?:[&\u00a7](?:[0-9a-fk-orA-FK-ORxX]|#[0-9a-fA-F]{6})|\\s)+$");

    /** Drops trailing spaces and colour codes, e.g. "&8[&3Mod&8] &3&o" -> "&8[&3Mod&8]". */
    public static String stripTrailing(String s) {
        return s == null ? "" : TRAILING.matcher(s).replaceAll("");
    }

    /**
     * The colour a player's name gets from a prefix, as a section-sign code, or null.
     * A colour left dangling at the end ("&8[&3Mod&8] &3") wins; otherwise the colour of the rank text itself
     * ("&8[&cManager&8] " gives &c, not the bracket colour).
     */
    public static String lastColor(String s) {
        if (s == null) return null;
        String t = color(s);
        String current = null, onText = null, trailing = null;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '§' && i + 1 < t.length()) {
                char c = Character.toLowerCase(t.charAt(i + 1));
                if (c == 'x' && i + 14 <= t.length()) {
                    current = trailing = t.substring(i, i + 14);
                    i += 13;
                } else {
                    if ("0123456789abcdef".indexOf(c) >= 0) current = trailing = t.substring(i, i + 2);
                    i++;
                }
            } else if (!Character.isWhitespace(ch)) {
                trailing = null;
                if (Character.isLetterOrDigit(ch)) onText = current;
            }
        }
        return trailing != null ? trailing : onText;
    }

    /** "done by luckperms command" -> "Done By Luckperms Command". */
    public static String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean start = true;
        for (char c : s.toCharArray()) {
            sb.append(start ? Character.toUpperCase(c) : c);
            start = Character.isWhitespace(c);
        }
        return sb.toString();
    }

    /** Replaces {key} with value for each key, value pair. */
    public static String apply(String s, String... placeholders) {
        if (s == null) return "";
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            s = s.replace("{" + placeholders[i] + "}", placeholders[i + 1] == null ? "" : placeholders[i + 1]);
        }
        return s;
    }

    private static final Pattern SPIGOT_HEX = Pattern.compile("[&§][xX]((?:[&§][0-9a-fA-F]){6})");
    private static final int[] LEGACY_RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    /** Translates &-codes and &#RRGGBB hex codes. Before 1.16, hex becomes the closest normal colour. */
    public static String color(String s) {
        if (s == null) return "";
        boolean hex = Version.hexColors();
        Matcher m = HEX.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String replacement;
            if (hex) {
                StringBuilder code = new StringBuilder("&x");
                for (char c : m.group(1).toCharArray()) code.append('&').append(c);
                replacement = code.toString();
            } else {
                replacement = "&" + nearestLegacy(Integer.parseInt(m.group(1), 16));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        String out = sb.toString();
        if (!hex) {
            // "&x&5&5&f&f&f&f" style codes (from LuckPerms prefixes) also become the closest normal colour.
            Matcher x = SPIGOT_HEX.matcher(out);
            StringBuffer sb2 = new StringBuffer();
            while (x.find()) {
                String digits = x.group(1).replaceAll("[&§]", "");
                x.appendReplacement(sb2, Matcher.quoteReplacement("&" + nearestLegacy(Integer.parseInt(digits, 16))));
            }
            x.appendTail(sb2);
            out = sb2.toString();
        }
        return ChatColor.translateAlternateColorCodes('&', out);
    }

    private static char nearestLegacy(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int best = 15;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < LEGACY_RGB.length; i++) {
            int lr = (LEGACY_RGB[i] >> 16) & 0xFF, lg = (LEGACY_RGB[i] >> 8) & 0xFF, lb = LEGACY_RGB[i] & 0xFF;
            long d = (long) (r - lr) * (r - lr) + (long) (g - lg) * (g - lg) + (long) (b - lb) * (b - lb);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return "0123456789abcdef".charAt(best);
    }
}
