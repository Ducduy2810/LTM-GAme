package vn.game.util;

import java.util.*;

/**
 * Tiện ích xử lý JSON thuần túy trong Java (Không cần bất kỳ thư viện bên ngoài như Jackson hay Gson).
 * Hỗ trợ parse JSON thành Map/List và format Map/List thành chuỗi JSON.
 */
public class JsonUtil {

    public static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) {
            return "\"" + escapeString((String) obj) + "\"";
        }
        if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        }
        if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escapeString(entry.getKey().toString())).append("\":");
                sb.append(toJson(entry.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        if (obj instanceof Collection) {
            Collection<?> col = (Collection<?>) obj;
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : col) {
                if (!first) sb.append(",");
                sb.append(toJson(item));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }
        if (obj.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(obj);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < length; i++) {
                if (i > 0) sb.append(",");
                sb.append(toJson(java.lang.reflect.Array.get(obj, i)));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeString(obj.toString()) + "\"";
    }

    private static String escapeString(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < ' ') {
                        String t = "000" + Integer.toHexString(c);
                        sb.append("\\u").append(t.substring(t.length() - 4));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object val = parse(json);
        if (val instanceof Map) {
            return (Map<String, Object>) val;
        }
        return new HashMap<>();
    }

    public static Object parse(String json) {
        if (json == null) return null;
        json = json.trim();
        if (json.isEmpty()) return null;
        int[] index = new int[]{0};
        return parseValue(json, index);
    }

    private static Object parseValue(String s, int[] index) {
        skipWhitespace(s, index);
        if (index[0] >= s.length()) return null;
        char c = s.charAt(index[0]);
        if (c == '{') return parseObjectInternal(s, index);
        if (c == '[') return parseArrayInternal(s, index);
        if (c == '"') return parseStringInternal(s, index);
        if (c == 't' || c == 'f') return parseBooleanInternal(s, index);
        if (c == 'n') return parseNullInternal(s, index);
        return parseNumberInternal(s, index);
    }

    private static Map<String, Object> parseObjectInternal(String s, int[] index) {
        Map<String, Object> map = new LinkedHashMap<>();
        index[0]++; // Bỏ qua '{'
        skipWhitespace(s, index);
        if (index[0] < s.length() && s.charAt(index[0]) == '}') {
            index[0]++;
            return map;
        }

        while (index[0] < s.length()) {
            skipWhitespace(s, index);
            String key = parseStringInternal(s, index);
            skipWhitespace(s, index);
            if (index[0] < s.length() && s.charAt(index[0]) == ':') {
                index[0]++;
            }
            skipWhitespace(s, index);
            Object value = parseValue(s, index);
            map.put(key, value);

            skipWhitespace(s, index);
            if (index[0] < s.length() && s.charAt(index[0]) == ',') {
                index[0]++;
            } else if (index[0] < s.length() && s.charAt(index[0]) == '}') {
                index[0]++;
                break;
            }
        }
        return map;
    }

    private static List<Object> parseArrayInternal(String s, int[] index) {
        List<Object> list = new ArrayList<>();
        index[0]++; // Bỏ qua '['
        skipWhitespace(s, index);
        if (index[0] < s.length() && s.charAt(index[0]) == ']') {
            index[0]++;
            return list;
        }

        while (index[0] < s.length()) {
            skipWhitespace(s, index);
            Object value = parseValue(s, index);
            list.add(value);

            skipWhitespace(s, index);
            if (index[0] < s.length() && s.charAt(index[0]) == ',') {
                index[0]++;
            } else if (index[0] < s.length() && s.charAt(index[0]) == ']') {
                index[0]++;
                break;
            }
        }
        return list;
    }

    private static String parseStringInternal(String s, int[] index) {
        StringBuilder sb = new StringBuilder();
        index[0]++; // Bỏ qua dấu " mở đầu
        while (index[0] < s.length()) {
            char c = s.charAt(index[0]++);
            if (c == '"') break;
            if (c == '\\' && index[0] < s.length()) {
                char next = s.charAt(index[0]++);
                switch (next) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (index[0] + 4 <= s.length()) {
                            String hex = s.substring(index[0], index[0] + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            index[0] += 4;
                        }
                        break;
                    default: sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Boolean parseBooleanInternal(String s, int[] index) {
        if (s.startsWith("true", index[0])) {
            index[0] += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", index[0])) {
            index[0] += 5;
            return Boolean.FALSE;
        }
        return Boolean.FALSE;
    }

    private static Object parseNullInternal(String s, int[] index) {
        if (s.startsWith("null", index[0])) {
            index[0] += 4;
        }
        return null;
    }

    private static Number parseNumberInternal(String s, int[] index) {
        int start = index[0];
        while (index[0] < s.length()) {
            char c = s.charAt(index[0]);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                index[0]++;
            } else {
                break;
            }
        }
        String numStr = s.substring(start, index[0]);
        if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
            return Double.parseDouble(numStr);
        } else {
            long l = Long.parseLong(numStr);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                return (int) l;
            }
            return l;
        }
    }

    private static void skipWhitespace(String s, int[] index) {
        while (index[0] < s.length()) {
            char c = s.charAt(index[0]);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                index[0]++;
            } else {
                break;
            }
        }
    }
}
