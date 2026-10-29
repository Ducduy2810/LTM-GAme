package vn.game.model;

import java.util.*;

/** Pure RP rules; all inputs come from the authoritative match result. */
public final class RankRules {
    private RankRules(){}
    private static final String[] TIERS={"BRONZE","SILVER","GOLD","PLATINUM","DIAMOND"};
    private static final String[] NAMES={"Đồng","Bạc","Vàng","Bạch Kim","Kim Cương"};
    public record Position(int step,int rp){
        public Position{if(step<0||step>14||rp<0||rp>1_000_000||step<14&&rp>99)throw new IllegalArgumentException("Rank/RP không hợp lệ.");}
        public int total(){return step*100+rp;}
        public String name(){return NAMES[step/3]+" "+(3-step%3);}
        public String tier(){return TIERS[step/3];}
        public Map<String,Object> json(){return Map.of("step",step,"rp",rp,"total",total(),"name",name(),"tier",tier());}
    }
    public static Position advance(Position before,int delta){
        return advance(before,delta,20,70);
    }
    public static Position advance(Position before,int delta,int buffer,int demotionRp){
        int step=before.step(),rp=before.rp();
        if(delta<0){
            if(rp==0&&step>0)return new Position(step-1,demotionRp);
            return new Position(step,Math.max(0,rp+delta));
        }
        rp+=delta;
        while(rp>=100&&step<14){rp-=100;step++;if(step%3==0)rp+=buffer;}
        return new Position(step,Math.min(1_000_000,rp));
    }
    public static double[] rewards(int players){
        if(players<2||players>4)throw new IllegalArgumentException("Trận PvP cần 2–4 người.");
        return players==2?new double[]{25,0}:players==3?new double[]{25,10,-10}:new double[]{25,10,-10,-15};
    }
    public static int tiedReward(int players,int first,int count){
        double[] rewards=rewards(players);double sum=0;for(int i=first;i<first+count;i++)sum+=rewards[i];
        return (int)Math.round(sum/count); // halves round toward the player, e.g. 17.5 -> 18.
    }
    public static int tiedReward(int players,int first,int count,Map<String,Object> rules){
        int[] rewards={((Number)rules.get("firstRP")).intValue(),((Number)rules.get(players==2?"duelSecondRP":"secondRP")).intValue(),((Number)rules.get("thirdRP")).intValue(),((Number)rules.get("fourthRP")).intValue()};
        double sum=0;for(int i=first;i<first+count;i++)sum+=rewards[i];return (int)Math.round(sum/count);
    }
    public static String title(int step,int serverPosition){return step<14||serverPosition<1||serverPosition>100?"":serverPosition<=10?"Huyền Thoại":"Cao Thủ";}
}
