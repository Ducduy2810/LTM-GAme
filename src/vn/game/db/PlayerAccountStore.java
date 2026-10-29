package vn.game.db;

import java.sql.*;
import java.util.*;
import vn.game.model.User;

/** Persistent account notices and read-only ranked profile, separate from casual scores. */
public final class PlayerAccountStore {
    private PlayerAccountStore() {}
    public static void init() throws SQLException {
        try(Connection c=AdminStore.connect();Statement st=c.createStatement()){
            st.execute("CREATE TABLE IF NOT EXISTS account_notices(id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER NOT NULL REFERENCES users(id),kind TEXT NOT NULL,reason TEXT NOT NULL,created_at INTEGER NOT NULL,acknowledged_at INTEGER)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_account_notices_user ON account_notices(user_id,id DESC)");
            st.execute("CREATE TABLE IF NOT EXISTS player_ranks(user_id INTEGER PRIMARY KEY REFERENCES users(id),current_tier TEXT NOT NULL DEFAULT 'UNRANKED',current_points INTEGER NOT NULL DEFAULT 0,highest_tier TEXT NOT NULL DEFAULT 'UNRANKED',highest_points INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL)");
        }
    }
    public static void addNotice(Connection c,int userId,String kind,String reason) throws SQLException {
        try(PreparedStatement ps=c.prepareStatement("INSERT INTO account_notices(user_id,kind,reason,created_at) VALUES(?,?,?,?)")){
            ps.setInt(1,userId);ps.setString(2,kind);ps.setString(3,reason);ps.setLong(4,System.currentTimeMillis());ps.executeUpdate();
        }
    }
    public static Map<String,Object> latestNotice(String username) throws SQLException {
        try(Connection c=AdminStore.connect()){
            List<Map<String,Object>> notices=AdminStore.rows(c,"SELECT n.id,n.kind,n.reason,n.created_at,u.blocked_until FROM account_notices n JOIN users u ON u.id=n.user_id WHERE u.username=? AND n.acknowledged_at IS NULL ORDER BY n.id DESC LIMIT 1",username);
            return notices.isEmpty()?Map.of():notices.get(0);
        }
    }
    public static Map<String,Object> restriction(String username) throws SQLException {
        try(Connection c=AdminStore.connect()){
            List<Map<String,Object>> found=AdminStore.rows(c,"SELECT blocked_until,blocked_reason FROM users WHERE username=?",username);
            if(found.isEmpty())return Map.of();Map<String,Object> row=found.get(0);long until=((Number)row.get("blocked_until")).longValue();
            if(until!=-1&&until<=System.currentTimeMillis())return Map.of();
            Map<String,Object> result=new LinkedHashMap<>();result.put("kind","LOCK");result.put("reason",row.get("blocked_reason"));result.put("blocked_until",until);return result;
        }
    }
    public static String restrictionMessage(Map<String,Object> value){
        String reason=Objects.toString(value.get("reason"),"");return "Tài khoản đã bị khóa."+(reason.isBlank()?"":" Lý do: "+reason);
    }
    public static void acknowledge(String username,int noticeId) throws SQLException {
        try(Connection c=AdminStore.connect();PreparedStatement ps=c.prepareStatement("UPDATE account_notices SET acknowledged_at=? WHERE user_id=(SELECT id FROM users WHERE username=?) AND id<=? AND acknowledged_at IS NULL")){
            ps.setLong(1,System.currentTimeMillis());ps.setString(2,username);ps.setInt(3,noticeId);ps.executeUpdate();
        }
    }
    public static String tierName(String tier){return switch(tier){case "BRONZE"->"Đồng";case "SILVER"->"Bạc";case "GOLD"->"Vàng";case "PLATINUM"->"Bạch Kim";case "DIAMOND"->"Kim Cương";case "MASTER"->"Cao Thủ";case "GRANDMASTER"->"Đại Cao Thủ";case "CHALLENGER"->"Thách Đấu";default->"Chưa xếp hạng";};}
    public static Map<String,Object> profile(String username) throws SQLException {
        try(Connection c=AdminStore.connect()){
            List<Map<String,Object>> found=AdminStore.rows(c,"SELECT id,username,email,google_id,password_hash FROM users WHERE username=?",username);
            if(found.isEmpty())throw new IllegalArgumentException("Không tìm thấy tài khoản.");Map<String,Object> user=found.get(0);
            try(PreparedStatement ps=c.prepareStatement("INSERT OR IGNORE INTO player_ranks(user_id,current_tier,highest_tier,updated_at) VALUES(?,'BRONZE','BRONZE',?)")){
                ps.setObject(1,user.get("id"));ps.setLong(2,System.currentTimeMillis());ps.executeUpdate();
            }
            List<Map<String,Object>> ranks=AdminStore.rows(c,"SELECT current_tier,current_points,highest_tier,highest_points FROM player_ranks WHERE user_id=?",user.get("id"));
            Map<String,Object> rank=ranks.isEmpty()?new LinkedHashMap<>(Map.of("current_tier","UNRANKED","current_points",0,"highest_tier","UNRANKED","highest_points",0)):new LinkedHashMap<>(ranks.get(0));
            rank.put("currentName",tierName((String)rank.get("current_tier")));rank.put("highestName",tierName((String)rank.get("highest_tier")));
            Map<String,Object> race=RaceStore.account(username);
            @SuppressWarnings("unchecked") Map<String,Object> current=(Map<String,Object>)race.get("current");
            @SuppressWarnings("unchecked") Map<String,Object> highest=(Map<String,Object>)race.get("highest");
            rank.put("currentName",current.get("name"));rank.put("highestName",highest.get("name"));rank.put("current_points",current.get("rp"));rank.put("highest_points",highest.get("rp"));rank.put("title",race.get("title"));rank.put("highestTitle",race.get("highestTitle"));
            return Map.of("username",username,"email",Objects.toString(user.get("email"),""),"googleLinked",user.get("google_id")!=null,"hasLocalPassword",user.get("password_hash")!=null,"rank",rank,"rankEnabled",true,"accountNotice",latestNotice(username),"race",race);
        }
    }
    public static void changePassword(String username,String current,String password) throws SQLException {
        if(password==null||password.isBlank()||password.length()<8||password.length()>128)throw new IllegalArgumentException("Mật khẩu mới cần 8–128 ký tự.");
        try(Connection c=AdminStore.connect()){
            c.setAutoCommit(false);
            try{
                List<Map<String,Object>> rows=AdminStore.rows(c,"SELECT id,password_hash,salt,role,blocked_until FROM users WHERE username=?",username);
                if(rows.isEmpty())throw new IllegalArgumentException("Tài khoản không tồn tại.");Map<String,Object> row=rows.get(0);
                if("admin".equalsIgnoreCase(Objects.toString(row.get("role"))))throw new IllegalArgumentException("Tài khoản quản trị dùng trang riêng.");
                if(row.get("password_hash")==null)throw new IllegalArgumentException("Tài khoản Google chưa có mật khẩu. Dùng Quên mật khẩu để nhận mã và đặt mật khẩu trước.");
                User user=new User();user.setSalt((String)row.get("salt"));user.setPasswordHash((String)row.get("password_hash"));
                if(!user.verifyPassword(current))throw new IllegalArgumentException("Mật khẩu hiện tại không đúng.");
                long until=((Number)row.get("blocked_until")).longValue();if(until==-1||until>System.currentTimeMillis())throw new IllegalArgumentException("Tài khoản đang bị khóa.");
                String salt=User.generateSalt();String hash=User.secureHashPassword(password,salt);
                try(PreparedStatement ps=c.prepareStatement("UPDATE users SET password_hash=?,salt=?,reset_token=NULL,reset_expires=0,reset_attempts=0,reset_sent_at=0 WHERE id=? AND password_hash=?")){
                    ps.setString(1,hash);ps.setString(2,salt);ps.setObject(3,row.get("id"));ps.setObject(4,row.get("password_hash"));if(ps.executeUpdate()!=1)throw new IllegalArgumentException("Tài khoản vừa thay đổi. Đăng nhập lại để tiếp tục.");
                }
                AdminStore.audit(c,username,"PLAYER_PASSWORD_CHANGE",username,Map.of());c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
    }
}
