import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import vn.game.db.*;
import vn.game.model.*;
import vn.game.server.*;
import vn.game.util.JsonUtil;

public class RankBackupIntegrationTest {
    static int checks;
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    static long number(Object value){return ((Number)value).longValue();}
    static long count(Map<String,Object> preview,String group){return number(map(map(preview.get(group)).get("race_settings")).get("questionCount"));}
    static byte[] hash(String name)throws Exception{return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of("data/backups",name)));}
    static void changeCount(int value)throws Exception{var settings=RaceStore.settings();settings.put("questionCount",value);RaceStore.saveSettings("test",settings);}
    static void backups()throws Exception{
        try(var c=AdminStore.connect();var st=c.createStatement()){
            st.executeUpdate("DELETE FROM app_settings WHERE key IN ('race_settings','runtime','announcement')");
            Files.createDirectories(Path.of("data/backups"));st.execute("VACUUM INTO 'data/backups/admin-legacy-defaults.db'");
        }
        String ten=AdminStore.backup("test");byte[] original=hash(ten);
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+Path.of("data/backups",ten).toAbsolutePath());var st=c.createStatement();var rs=st.executeQuery("SELECT value FROM app_settings WHERE key='race_settings'")){
            check(rs.next(),"implicit rank defaults are stored inside the backup file");check(number(JsonUtil.parseObject(rs.getString(1)).get("questionCount"))==10,"file contains ten questions at creation");
        }
        changeCount(15);
        var preview=AdminStore.backupConfiguration(ten);
        check(count(preview,"snapshot")==10,"snapshot remains ten after live changes to fifteen");check(count(preview,"values")==10,"apply uses ten from the selected backup");check(count(preview,"current")==15,"comparison separately reports current fifteen");check(Arrays.equals(original,hash(ten)),"viewing backup never mutates its bytes");
        var legacy=AdminStore.backupConfiguration("admin-legacy-defaults.db");check(count(legacy,"snapshot")==10,"legacy missing defaults resolve to ten, never current fifteen");check(!((List<?>)legacy.get("warnings")).isEmpty(),"legacy defaults are identified explicitly");
        String fifteen=AdminStore.backup("test");byte[] second=hash(fifteen);changeCount(12);
        check(count(AdminStore.backupConfiguration(ten),"snapshot")==10,"first backup still ten");check(count(AdminStore.backupConfiguration(fifteen),"snapshot")==15,"second backup still fifteen");check(Arrays.equals(second,hash(fifteen)),"second backup remains unchanged");
        var restored=AdminStore.applyBackupConfiguration("test",ten,(String)preview.get("fingerprint"));
        check(number(RaceStore.settings().get("questionCount"))==10,"applying old backup restores ten to live settings");
        check(count(AdminStore.backupConfiguration((String)restored.get("safetyBackup")),"snapshot")==12,"automatic safety backup records twelve before restore");check(Arrays.equals(original,hash(ten)),"restore never rewrites the selected old backup");
        boolean rejected=false;try{AdminStore.applyBackupConfiguration("test",ten,"0".repeat(64));}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"wrong snapshot fingerprint cannot be applied");
        var controller=new AdminController(new UserRepository(DatabaseManager.getInstance()),new AdminController.Runtime(){public Map<String,Object> rooms(){return Map.of();}public int connections(){return 0;}public void disconnect(String user,String reason){}public void roomAction(String actor,String game,String room,String action,String target,String reason){}});
        check(controller.handle("POST","/api/admin/backup-upload","",new byte[0],Map.of("content-type","application/octet-stream"),"127.0.0.1").status()==404,"upload API is removed");
    }
    static void ranks()throws Exception{
        check(RankRules.advance(new RankRules.Position(14,99),25).equals(new RankRules.Position(15,44)),"Diamond I promotes to Master");
        check(RankRules.advance(new RankRules.Position(15,10_000_000_000L),25).rp()==10_000_000_025L,"Master has no former million-point cap or 32-bit overflow");
        check(RankRules.advance(new RankRules.Position(15,5),-15).equals(new RankRules.Position(15,0)),"Master loss stops at zero first");check(RankRules.advance(new RankRules.Position(15,0),-15).equals(new RankRules.Position(14,70)),"loss from zero demotes to Diamond I");
        check(RankRules.title(14,1).isEmpty(),"Diamond I is never Legendary");check(RankRules.title(15,10).equals("Huyền Thoại"),"tenth Master is Legendary");check(RankRules.title(15,11).equals("Cao Thủ"),"eleventh stays Master");check(RankRules.title(15,1000).equals("Cao Thủ"),"Master is not restricted to top one hundred");
        var users=new UserRepository(DatabaseManager.getInstance());
        for(int i=0;i<12;i++){
            String name="master"+i;users.register(name,name+"@example.com","PlayerPass123");
            RaceStore.adminPlayer("test",name,"setRank",Map.of("step",15,"rp",10_000_000_000L+i*100L),"Rank fixture");
            try(var c=AdminStore.connect();var ps=c.prepareStatement("UPDATE player_ranks SET ranked_games=1 WHERE user_id=(SELECT id FROM users WHERE username=?)")){ps.setString(1,name);ps.executeUpdate();}
        }
        users.register("diamond","diamond@example.com","PlayerPass123");RaceStore.adminPlayer("test","diamond","setRank",Map.of("step",14,"rp",99),"Rank fixture");
        try(var c=AdminStore.connect();var st=c.createStatement()){st.executeUpdate("UPDATE player_ranks SET ranked_games=1 WHERE user_id=(SELECT id FROM users WHERE username='diamond')");}
        var leaderboard=RaceStore.leaderboard();check(leaderboard.size()==13,"all eligible players on board");
        for(int i=0;i<12;i++){var row=leaderboard.get(i);check(row.get("rankName").equals("Cao Thủ"),"Master has no numbered divisions");check(row.get("title").equals(i<10?"Huyền Thoại":"Cao Thủ"),"title follows live Master ordering "+i);}
        check(leaderboard.get(0).get("username").equals("master11"),"highest Master points lead the board");check(leaderboard.get(12).get("title").equals(""),"lower rank cannot obtain an elite title");
        check(number(map(RaceStore.account("master0").get("current")).get("rp"))==10_000_000_000L,"account API preserves large RP");
        users.register("legacy","legacy@example.com","PlayerPass123");RaceStore.account("legacy");
        try(var c=AdminStore.connect();var st=c.createStatement()){st.executeUpdate("UPDATE player_ranks SET current_step=14,current_tier='DIAMOND',current_points=250,highest_step=14,highest_tier='DIAMOND',highest_points=350 WHERE user_id=(SELECT id FROM users WHERE username='legacy')");}
        RaceStore.init();var legacy=RaceStore.account("legacy");check(number(map(legacy.get("current")).get("step"))==15&&number(map(legacy.get("current")).get("rp"))==150,"legacy Diamond surplus migrated without losing total RP");
        check(number(map(legacy.get("highest")).get("rp"))==250,"highest legacy rank migrated too");RaceStore.init();check(number(map(RaceStore.account("legacy").get("current")).get("rp"))==150,"migration is idempotent");
    }
    public static void main(String[] args)throws Exception{
        if(!Files.exists(Path.of(".rank-backup-test")))throw new IllegalStateException("Isolated test directory required");
        var db=DatabaseManager.getInstance();db.init();try{backups();ranks();System.out.println("PASS: "+checks+" Master/Legendary, large RP, legacy migration, immutable backup, defaults, restore and removed-upload checks.");}finally{db.close();}
    }
}
