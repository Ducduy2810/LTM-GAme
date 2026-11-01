package vn.game.model;

import java.sql.*;
import java.util.*;
import vn.game.db.AdminStore;
import vn.game.util.JsonUtil;

public final class ServerAnnouncement {
    private ServerAnnouncement(){}
    public static Map<String,Object> read() throws SQLException {
        try(Connection c=AdminStore.connect();PreparedStatement ps=c.prepareStatement("SELECT value FROM app_settings WHERE key='announcement'" );ResultSet rs=ps.executeQuery()){
            return rs.next()?JsonUtil.parseObject(rs.getString(1)):Map.of("enabled",false,"text","","id","","expiresAt",0,"publishedAt",0);
        }
    }
    public static Map<String,Object> publicView() throws SQLException {
        Map<String,Object> value=read();boolean enabled=Boolean.TRUE.equals(value.get("enabled"));long expires=value.get("expiresAt") instanceof Number n?n.longValue():0;
        if(expires>0&&expires<=System.currentTimeMillis())enabled=false;
        return Map.of("enabled",enabled,"text",enabled?Objects.toString(value.get("text"),""):"","id",Objects.toString(value.get("id"),""),"expiresAt",expires);
    }
    public static void save(String actor,Map<String,Object> input) throws SQLException {
        if(!(input.get("enabled") instanceof Boolean))throw new IllegalArgumentException("Trạng thái thông báo không hợp lệ.");
        boolean enabled=Boolean.TRUE.equals(input.get("enabled"));String text=AdminStore.text(input,"text");
        if(enabled&&(text.isEmpty()||text.length()>500))throw new IllegalArgumentException("Thông báo cần 1–500 ký tự.");
        int minutes=input.get("minutes") instanceof Number n?n.intValue():0;
        if(minutes<0||minutes>10080||(input.get("minutes") instanceof Number n&&n.doubleValue()!=minutes))throw new IllegalArgumentException("Thời hạn thông báo cần 0–10080 phút.");
        long now=System.currentTimeMillis();Map<String,Object> value=Map.of("enabled",enabled,"text",text,"id",UUID.randomUUID().toString(),"publishedAt",now,"expiresAt",minutes==0?0:now+minutes*60_000L,"publishedBy",actor);
        try(Connection c=AdminStore.connect()){
            c.setAutoCommit(false);try{
                try(PreparedStatement ps=c.prepareStatement("INSERT INTO app_settings(key,value) VALUES('announcement',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){ps.setString(1,JsonUtil.toJson(value));ps.executeUpdate();}
                AdminStore.audit(c,actor,enabled?"SERVER_ANNOUNCE":"SERVER_ANNOUNCEMENT_CLEAR","server",value);c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
    }
}
