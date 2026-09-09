package com.github.catvod.spider;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ScriptJson — 引擎内部用的小工具：JS/JSON 字符串转义、参数解析。
 */
final class ScriptJson {

    private ScriptJson() {}

    /** 生成 JS 字符串字面量（含两侧引号） */
    static String js(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\'': sb.append("\\'"); break;
                default:
                    if (c < 0x20) sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** 统一错误返回，避免各处拼 JSON */
    static String error(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", message == null ? "" : message);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    /** 去掉首尾空白；null -> "" */
    static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    static boolean empty(String s) {
        return s == null || s.trim().length() == 0;
    }

    /** 把 JSON 数组字符串转成 Object[]，解析失败则当成单个字符串参数 */
    static Object[] toArgs(String argsJson) {
        String s = safe(argsJson);
        if (s.length() == 0) return new Object[0];
        try {
            JSONArray arr = new JSONArray(s);
            Object[] out = new Object[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                Object v = arr.opt(i);
                if (v == null) {
                    out[i] = "";
                } else if (v instanceof JSONObject || v instanceof JSONArray) {
                    /* Python 侧拿不到 org.json 对象，直接传 JSON 文本，脚本里 json.loads */
                    out[i] = v.toString();
                } else {
                    out[i] = v;
                }
            }
            return out;
        } catch (Throwable t) {
            return new Object[]{s};
        }
    }

    /** 组装 JSON 数组字符串，元素已是可序列化的值 */
    static String array(Object... values) {
        JSONArray arr = new JSONArray();
        for (Object v : values) {
            if (v == null) arr.put(JSONObject.NULL);
            else arr.put(v);
        }
        return arr.toString();
    }

    static String optText(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            String v = TextUtils.isEmpty(o.optString(k, "")) ? "" : o.optString(k, "");
            if (v.length() > 0) return v;
        }
        return "";
    }
}
