package dev.grants.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Durations {

    public static final long PERMANENT = 0L;
    public static final long INVALID = -1L;

    private static final long S = 1000L, M = 60 * S, H = 60 * M, D = 24 * H, W = 7 * D, MO = 30 * D, Y = 365 * D;
    private static final Pattern PART = Pattern.compile("(\\d+)(mo|y|w|d|h|m|s)");

    /** permanent, day, days, hour, hours, minute, minutes, second, seconds */
    private static String[] words = {"Permanent", "day", "days", "hour", "hours", "minute", "minutes", "second", "seconds"};

    private Durations() {}

    public static void setWords(String[] newWords) {
        if (newWords.length == 9) words = newWords;
    }

    /** Parses "5m", "1d12h", "permanent" etc. Returns millis, PERMANENT, or INVALID. */
    public static long parse(String input) {
        if (input == null) return INVALID;
        String s = input.toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.equals("perm") || s.equals("permanent") || s.equals("forever") || s.equals("never")) return PERMANENT;
        if (s.isEmpty()) return INVALID;
        Matcher m = PART.matcher(s);
        long total = 0;
        int pos = 0;
        while (m.find()) {
            if (m.start() != pos) return INVALID;
            long n;
            try {
                n = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return INVALID;
            }
            long unit;
            switch (m.group(2)) {
                case "s": unit = S; break;
                case "m": unit = M; break;
                case "h": unit = H; break;
                case "d": unit = D; break;
                case "w": unit = W; break;
                case "mo": unit = MO; break;
                default: unit = Y;
            }
            total += n * unit;
            pos = m.end();
        }
        if (pos != s.length() || total <= 0) return INVALID;
        return total;
    }

    /** 90 minutes -> "1 hour 30 minutes"; 0 -> "Permanent". */
    public static String format(long millis) {
        if (millis <= 0) return words[0];
        List<String> parts = new ArrayList<>();
        long rest = millis;
        rest = take(parts, rest, D, 1);
        rest = take(parts, rest, H, 3);
        rest = take(parts, rest, M, 5);
        take(parts, rest, S, 7);
        if (parts.isEmpty()) return "1 " + words[7];
        return String.join(" ", parts);
    }

    private static long take(List<String> parts, long rest, long unit, int word) {
        long n = rest / unit;
        if (n > 0) parts.add(n + " " + (n == 1 ? words[word] : words[word + 1]));
        return rest % unit;
    }
}
