package vn.game.util;

import java.util.*;

/** UTF-8 CSV, quoted commas, escaped quotes and embedded newlines. */
public final class ContentCsv {
    private ContentCsv() {}
    public static List<Map<String,Object>> parse(String kind,String input) {
        if(input==null||input.length()>450_000)throw new IllegalArgumentException("CSV quá lớn (tối đa 450.000 ký tự).");
        String csv=input.startsWith("\uFEFF")?input.substring(1):input;
        List<List<String>> records=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder field=new StringBuilder();boolean quoted=false,closed=false;
        for(int i=0;i<csv.length();i++){
            char ch=csv.charAt(i);
            if(quoted){if(ch=='"'){if(i+1<csv.length()&&csv.charAt(i+1)=='"'){field.append('"');i++;}else{quoted=false;closed=true;}}else field.append(ch);continue;}
            if(ch=='"'){if(field.length()!=0||closed)throw new IllegalArgumentException("Dấu ngoặc kép CSV không hợp lệ.");quoted=true;continue;}
            if(ch==','||ch=='\r'||ch=='\n'){
                row.add(field.toString());field.setLength(0);closed=false;
                if(ch!=','){if(ch=='\r'&&i+1<csv.length()&&csv.charAt(i+1)=='\n')i++;if(row.stream().anyMatch(s->!s.isBlank()))records.add(row);row=new ArrayList<>();if(records.size()>501)throw new IllegalArgumentException("Chỉ nhập tối đa 500 dòng mỗi lần.");}
            }else{if(closed)throw new IllegalArgumentException("Có ký tự sau dấu ngoặc kép CSV.");field.append(ch);}
        }
        if(quoted)throw new IllegalArgumentException("CSV có chuỗi chưa đóng ngoặc kép.");
        row.add(field.toString());if(row.stream().anyMatch(s->!s.isBlank()))records.add(row);
        if(records.size()<2||records.size()>501)throw new IllegalArgumentException("CSV cần tiêu đề và 1–500 dòng dữ liệu.");
        List<String> headers=records.remove(0).stream().map(s->s.trim().toLowerCase(Locale.ROOT)).toList();
        if(new HashSet<>(headers).size()!=headers.size())throw new IllegalArgumentException("CSV có tiêu đề cột trùng nhau.");
        List<String> required="QUESTION".equals(kind)?List.of("text","option_a","option_b","option_c","option_d","correct","topic","difficulty"):List.of("phrase","hint","topic");
        if(!headers.containsAll(required))throw new IllegalArgumentException("CSV thiếu cột. Cần: "+String.join(", ",required));
        List<Map<String,Object>> result=new ArrayList<>();
        for(int i=0;i<records.size();i++){
            List<String> values=records.get(i);if(values.size()!=headers.size())throw new IllegalArgumentException("Dòng dữ liệu "+(i+1)+" có số cột không đúng.");
            Map<String,Object> v=new LinkedHashMap<>();for(int j=0;j<headers.size();j++)v.put(headers.get(j),values.get(j).trim());
            if("QUESTION".equals(kind)){
                v.put("options",List.of(v.get("option_a"),v.get("option_b"),v.get("option_c"),v.get("option_d")));
                String correct=v.get("correct").toString().toUpperCase(Locale.ROOT);int index="ABCD".indexOf(correct);
                if(correct.length()!=1||index<0)throw new IllegalArgumentException("Dòng dữ liệu "+(i+1)+": correct phải là A, B, C hoặc D.");v.put("correct",index);
            }
            if(!v.containsKey("status")||v.get("status").toString().isBlank())v.put("status","DRAFT");
            result.add(v);
        }return result;
    }
}
