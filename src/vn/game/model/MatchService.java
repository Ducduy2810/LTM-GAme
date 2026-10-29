package vn.game.model;

import java.sql.*;
import java.util.*;
import vn.game.util.JsonUtil;

/** Durable, non-ranked match history shared by the three legacy games. */
public final class MatchService {
    private MatchService() {}
    public record Result(String username, int score, int placement, String outcome) {}

    private static Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:data/game.db");
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    public static void init() throws SQLException {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS matches (
                    id TEXT PRIMARY KEY,
                    game_type TEXT NOT NULL CHECK(game_type IN ('ARENA','WHEEL','SURVIVAL')),
                    mode TEXT NOT NULL CHECK(mode IN ('CASUAL','BOT')),
                    room_code TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('ACTIVE','COMPLETED','CANCELLED','INTERRUPTED')),
                    config_json TEXT NOT NULL,
                    started_at INTEGER NOT NULL,
                    ended_at INTEGER,
                    reason TEXT NOT NULL DEFAULT ''
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS match_players (
                    match_id TEXT NOT NULL REFERENCES matches(id),
                    user_id INTEGER REFERENCES users(id),
                    username TEXT NOT NULL COLLATE NOCASE,
                    score INTEGER NOT NULL DEFAULT 0,
                    placement INTEGER NOT NULL DEFAULT 0,
                    outcome TEXT NOT NULL DEFAULT 'LEFT',
                    PRIMARY KEY(match_id, username)
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_match_players_user ON match_players(user_id, match_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_matches_time ON matches(started_at DESC)");
        }
    }

    public static void recoverInterrupted() throws SQLException {
        try (Connection c=connect(); Statement st=c.createStatement()) {
            // Only called at process startup, not when reconnecting a JDBC connection.
            st.executeUpdate("UPDATE matches SET status='INTERRUPTED', ended_at=" + System.currentTimeMillis()
                    + ", reason='Máy chủ khởi động lại' WHERE status='ACTIVE'");
        }
    }

    public static String start(String game, String room, boolean hasBots, Map<String,Object> config, Collection<String> users) {
        String id = UUID.randomUUID().toString();
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO matches VALUES(?,?,?,?, 'ACTIVE',?,?,NULL,'')")) {
                    ps.setString(1,id); ps.setString(2,game); ps.setString(3,hasBots ? "BOT" : "CASUAL");
                    ps.setString(4,room); ps.setString(5,JsonUtil.toJson(config)); ps.setLong(6,System.currentTimeMillis());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO match_players(match_id,user_id,username) VALUES(?,(SELECT id FROM users WHERE username=?),?)")) {
                    for (String user : users) {
                        ps.setString(1,id); ps.setString(2,user); ps.setString(3,user); ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
                return id;
            } catch (SQLException e) { c.rollback(); throw e; }
        } catch (SQLException e) {
            System.err.println("Không thể lưu trận mới: " + e.getMessage());
            return null;
        }
    }

    /** Compare-and-set plus transaction: duplicate completion cannot overwrite a result. */
    public static boolean finish(String id, boolean cancelled, String reason, Collection<Result> results) {
        if (id == null) return false;
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("UPDATE matches SET status=?,ended_at=?,reason=? WHERE id=? AND status='ACTIVE'")) {
                    ps.setString(1,cancelled ? "CANCELLED" : "COMPLETED"); ps.setLong(2,System.currentTimeMillis());
                    ps.setString(3,reason == null ? "" : reason); ps.setString(4,id);
                    if (ps.executeUpdate() == 0) { c.rollback(); return false; }
                }
                if (cancelled) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE match_players SET outcome='CANCELLED' WHERE match_id=?")) {
                        ps.setString(1,id); ps.executeUpdate();
                    }
                } else try (PreparedStatement ps = c.prepareStatement("UPDATE match_players SET score=?,placement=?,outcome=? WHERE match_id=? AND username=?")) {
                    for (Result r : results) {
                        ps.setInt(1,r.score()); ps.setInt(2,r.placement()); ps.setString(3,r.outcome());
                        ps.setString(4,id); ps.setString(5,r.username()); ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit(); return true;
            } catch (SQLException e) { c.rollback(); throw e; }
        } catch (SQLException e) { System.err.println("Không thể lưu kết quả: " + e.getMessage()); return false; }
    }

    public static List<Map<String,Object>> history(String username) throws SQLException {
        List<Map<String,Object>> rows = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("""
            SELECT m.*,p.score,p.placement,p.outcome FROM matches m
            JOIN match_players p ON p.match_id=m.id
            WHERE p.user_id=(SELECT id FROM users WHERE username=?)
            ORDER BY m.started_at DESC LIMIT 30
            """)) {
            ps.setString(1,username);
            try (ResultSet rs=ps.executeQuery()) {
                while(rs.next()) {
                    Map<String,Object> row=new LinkedHashMap<>();
                    for(String key: List.of("id","game_type","mode","room_code","status","reason","outcome")) row.put(key,rs.getString(key));
                    for(String key: List.of("started_at","score","placement")) row.put(key,rs.getLong(key));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    public static void departure(String id,String username,int score,String outcome) {
        if(id==null)return;
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement("UPDATE match_players SET score=?,outcome=? WHERE match_id=? AND username=? AND EXISTS(SELECT 1 FROM matches WHERE id=? AND status='ACTIVE')")){
            ps.setInt(1,score);ps.setString(2,outcome);ps.setString(3,id);ps.setString(4,username);ps.setString(5,id);ps.executeUpdate();
        }catch(SQLException e){throw new IllegalStateException("Không lưu được trạng thái rời trận",e);}
    }
}
