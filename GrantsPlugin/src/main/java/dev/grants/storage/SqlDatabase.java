package dev.grants.storage;

import dev.grants.model.Grant;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Grant history + cross-server messages in MySQL / MariaDB / PostgreSQL, shared by every server
 * pointed at the same database. All queries run one at a time on a single background thread.
 */
public final class SqlDatabase {

    public enum Type { MYSQL, MARIADB, POSTGRESQL }

    /** A message waiting to be shown to a player on whichever server they're on. */
    public static final class Message {
        public final long id;
        public final UUID target;
        public final String text;

        Message(long id, UUID target, String text) {
            this.id = id;
            this.target = target;
            this.text = text;
        }
    }

    /** Work that may throw; run on the database thread. */
    public interface Task {
        void run() throws Exception;
    }

    private final Logger log;
    private final Type type;
    /** host:port/database */
    private final String target;
    private final Properties props;
    private final String history;
    private final String messages;
    private final String colors;

    /** Messages addressed to this id are instructions for every server, not chat for a player. */
    public static final UUID BROADCAST = new UUID(0L, 0L);
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Grants-Database");
        t.setDaemon(true);
        return t;
    });

    private Driver driver;
    private Connection connection;
    private String driverSource = "";

    public SqlDatabase(Logger log, Type type, String address, String database, String username, String password,
                       String tablePrefix, Map<String, Object> extraProperties) {
        this.log = log;
        this.type = type;
        String host = address;
        String port = type == Type.POSTGRESQL ? "5432" : "3306";
        int colon = address.lastIndexOf(':');
        if (colon > 0) {
            host = address.substring(0, colon);
            port = address.substring(colon + 1);
        }
        this.target = host + ":" + port + "/" + database;
        this.props = new Properties();
        if (extraProperties != null) {
            for (Map.Entry<String, Object> e : extraProperties.entrySet()) props.setProperty(e.getKey(), String.valueOf(e.getValue()));
        }
        props.setProperty("user", username);
        props.setProperty("password", password == null ? "" : password);
        String prefix = tablePrefix.replaceAll("[^A-Za-z0-9_]", "");
        this.history = prefix + "history";
        this.messages = prefix + "messages";
        this.colors = prefix + "colors";
    }

    // ---------------------------------------------------------------- setup

    /** Finds a driver, connects and creates the tables. Blocking: call from the database thread. */
    public void connect(File luckPermsLibs) throws Exception {
        driver = findDriver(luckPermsLibs);
        if (driver == null) {
            throw new SQLException("No " + type.name().toLowerCase(Locale.ROOT) + " JDBC driver found. Start LuckPerms with the "
                    + "same storage-method once so it downloads one into plugins/LuckPerms/libs.");
        }
        connectionUrl = "jdbc:" + scheme + "://" + target;
        log.info("Using database driver " + driverSource + " -> " + connectionUrl);
        createTables();
    }

    private String connectionUrl;
    /** mysql / mariadb / postgresql, matching the driver that was found. */
    private String scheme = "mysql";

    private Connection conn() throws SQLException {
        if (connection != null) {
            try {
                if (connection.isValid(2)) return connection;
            } catch (SQLException ignored) {
                // reconnect below
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
                // already broken
            }
        }
        connection = driver.connect(connectionUrl, props);
        if (connection == null) throw new SQLException("Driver refused URL " + connectionUrl);
        return connection;
    }

    private void createTables() throws SQLException {
        String idCol = type == Type.POSTGRESQL ? "BIGSERIAL PRIMARY KEY" : "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
        try (Statement s = conn().createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + history + " ("
                    + "id VARCHAR(36) NOT NULL PRIMARY KEY, "
                    + "target VARCHAR(36) NOT NULL, "
                    + "target_name VARCHAR(32), "
                    + "rank_group VARCHAR(64) NOT NULL, "
                    + "granted_by VARCHAR(64), "
                    + "granted_at BIGINT NOT NULL, "
                    + "expires_at BIGINT NOT NULL, "
                    + "reason VARCHAR(255), "
                    + "status VARCHAR(16) NOT NULL, "
                    + "ended_at BIGINT NOT NULL, "
                    + "removed_by VARCHAR(64))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + colors + " ("
                    + "rank_group VARCHAR(64) NOT NULL PRIMARY KEY, "
                    + "color VARCHAR(16) NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + messages + " ("
                    + "id " + idCol + ", "
                    + "target VARCHAR(36) NOT NULL, "
                    + "message TEXT NOT NULL, "
                    + "created BIGINT NOT NULL)");
        }
        // Index on the player column; "already exists" errors are expected after the first start.
        try (Statement s = conn().createStatement()) {
            s.executeUpdate("CREATE INDEX " + history + "_target ON " + history + " (target)");
        } catch (SQLException ignored) {
            // exists
        }
    }

    /** Looks in LuckPerms' downloaded libraries first (they're newer), then in what the server ships with. */
    private Driver findDriver(File lpLibs) {
        String want = type == Type.POSTGRESQL ? "postgresql" : type == Type.MARIADB ? "mariadb" : "mysql";
        Driver d = driverFromLibs(lpLibs, want);
        if (d != null) {
            scheme = want;
            return d;
        }
        if (type == Type.MARIADB) {
            d = driverFromLibs(lpLibs, "mysql"); // MySQL drivers talk to MariaDB too
            if (d != null) {
                scheme = "mysql";
                return d;
            }
        }

        List<String> bundled = new ArrayList<String>();
        if (type == Type.POSTGRESQL) {
            bundled.add("org.postgresql.Driver");
        } else {
            if (type == Type.MARIADB) bundled.add("org.mariadb.jdbc.Driver");
            bundled.add("com.mysql.cj.jdbc.Driver");
            bundled.add("com.mysql.jdbc.Driver");
        }
        for (String cls : bundled) {
            try {
                Driver bundledDriver = (Driver) Class.forName(cls).newInstance();
                driverSource = cls + " (bundled with the server)";
                scheme = cls.contains("postgresql") ? "postgresql" : cls.contains("mariadb") ? "mariadb" : "mysql";
                return bundledDriver;
            } catch (Throwable ignored) {
                // try next
            }
        }
        return null;
    }

    private Driver driverFromLibs(File dir, String want) {
        File[] jars = dir == null ? null : dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null) return null;
        for (File jar : jars) {
            String cls = null;
            try (ZipFile zip = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.endsWith("/Driver.class") || !name.toLowerCase(Locale.ROOT).contains(want)) continue;
                    String candidate = name.substring(0, name.length() - 6).replace('/', '.');
                    if (cls == null || candidate.length() < cls.length() || candidate.contains(".cj.")) cls = candidate;
                }
            } catch (Exception e) {
                continue;
            }
            if (cls == null) continue;
            try {
                // Isolated loader so it can't clash with a driver the server already ships.
                URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()},
                        ClassLoader.getSystemClassLoader().getParent());
                Driver d = (Driver) Class.forName(cls, true, loader).newInstance();
                driverSource = jar.getName();
                return d;
            } catch (Throwable t) {
                log.log(Level.FINE, "Could not load driver " + cls + " from " + jar.getName(), t);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- threading

    public void async(Task task) {
        thread.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.log(Level.WARNING, "Database error: " + e.getMessage(), e);
            }
        });
    }

    public void close() {
        thread.shutdown();
        try {
            thread.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // closing anyway
            }
        }
    }

    // ---------------------------------------------------------------- grants

    public List<Grant> loadGrants(UUID player) throws SQLException {
        List<Grant> out = new ArrayList<Grant>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT * FROM " + history + " WHERE target = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        out.add(new Grant(
                                UUID.fromString(rs.getString("id")),
                                UUID.fromString(rs.getString("target")),
                                rs.getString("target_name"),
                                rs.getString("rank_group"),
                                rs.getString("granted_by"),
                                rs.getLong("granted_at"),
                                rs.getLong("expires_at"),
                                rs.getString("reason"),
                                Grant.Status.valueOf(rs.getString("status")),
                                rs.getLong("ended_at"),
                                rs.getString("removed_by")));
                    } catch (IllegalArgumentException e) {
                        log.warning("Skipping broken grant row " + rs.getString("id"));
                    }
                }
            }
        }
        return out;
    }

    /** Insert or update one grant (works the same on MySQL, MariaDB and PostgreSQL). */
    public void saveGrant(Grant g) throws SQLException {
        try (PreparedStatement up = conn().prepareStatement("UPDATE " + history + " SET target_name = ?, rank_group = ?, "
                + "granted_by = ?, granted_at = ?, expires_at = ?, reason = ?, status = ?, ended_at = ?, removed_by = ? "
                + "WHERE id = ?")) {
            up.setString(1, g.targetName());
            up.setString(2, g.group());
            up.setString(3, g.grantedBy());
            up.setLong(4, g.grantedAt());
            up.setLong(5, g.expiresAt());
            up.setString(6, cut(g.reason(), 255));
            up.setString(7, g.status().name());
            up.setLong(8, g.endedAt());
            up.setString(9, g.removedBy());
            up.setString(10, g.id().toString());
            if (up.executeUpdate() > 0) return;
        }
        try (PreparedStatement in = conn().prepareStatement("INSERT INTO " + history + " (id, target, target_name, rank_group, "
                + "granted_by, granted_at, expires_at, reason, status, ended_at, removed_by) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            in.setString(1, g.id().toString());
            in.setString(2, g.target().toString());
            in.setString(3, g.targetName());
            in.setString(4, g.group());
            in.setString(5, g.grantedBy());
            in.setLong(6, g.grantedAt());
            in.setLong(7, g.expiresAt());
            in.setString(8, cut(g.reason(), 255));
            in.setString(9, g.status().name());
            in.setLong(10, g.endedAt());
            in.setString(11, g.removedBy());
            in.executeUpdate();
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    // ---------------------------------------------------------------- rank colours

    /** rank (lower case) -> colour, shared by every server. */
    public Map<String, String> loadColors() throws SQLException {
        Map<String, String> out = new java.util.LinkedHashMap<String, String>();
        try (Statement s = conn().createStatement();
             ResultSet rs = s.executeQuery("SELECT rank_group, color FROM " + colors)) {
            while (rs.next()) out.put(rs.getString(1), rs.getString(2));
        }
        return out;
    }

    public void saveColor(String rank, String color) throws SQLException {
        try (PreparedStatement up = conn().prepareStatement("UPDATE " + colors + " SET color = ? WHERE rank_group = ?")) {
            up.setString(1, color);
            up.setString(2, rank);
            if (up.executeUpdate() > 0) return;
        }
        try (PreparedStatement in = conn().prepareStatement("INSERT INTO " + colors + " (rank_group, color) VALUES (?, ?)")) {
            in.setString(1, rank);
            in.setString(2, color);
            in.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- cross-server messages

    public long latestMessageId() throws SQLException {
        try (Statement s = conn().createStatement(); ResultSet rs = s.executeQuery("SELECT MAX(id) FROM " + messages)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public void sendMessage(UUID target, String text) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "INSERT INTO " + messages + " (target, message, created) VALUES (?, ?, ?)")) {
            ps.setString(1, target.toString());
            ps.setString(2, text);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public List<Message> messagesAfter(long id) throws SQLException {
        List<Message> out = new ArrayList<Message>();
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT id, target, message FROM " + messages + " WHERE id > ? ORDER BY id")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        out.add(new Message(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3)));
                    } catch (IllegalArgumentException ignored) {
                        // bad uuid, skip
                    }
                }
            }
        }
        return out;
    }

    /** Old messages have been seen by every server by now. */
    public void deleteMessagesBefore(long time) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + messages + " WHERE created < ?")) {
            ps.setLong(1, time);
            ps.executeUpdate();
        }
    }
}
