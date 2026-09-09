package com.github.catvod.spider;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ScriptEngine — 内置脚本（assets/js、assets/py）的统一入口。
 *
 * <p>脚本标识（下面统称 spec）支持这几种写法：
 * <pre>
 *   js:demo          py:demo
 *   demo.js          demo.py
 *   demo                       （先按 js 找，再按 py 找）
 *   {"engine":"js","script":"demo"}
 * </pre>
 * 也可以带入口参数：js:demo?func=hello
 *
 * <p>典型调用：
 * <pre>
 *   ScriptEngine.attach(context);
 *   String json = ScriptEngine.call("js:demo", "homeContent", "[true]");
 * </pre>
 */
public final class ScriptEngine {

    private static final int DEFAULT_TIMEOUT = 20000;

    private ScriptEngine() {}

    public static void attach(Context context) {
        JsRunner.attach(context);
        PyRunner.attach(context);
    }

    public static String call(String spec, String func, String argsJson) {
        return call(spec, func, argsJson, DEFAULT_TIMEOUT);
    }

    public static String call(String spec, String func, String argsJson, int timeoutMs) {
        String key = resolve(spec);
        if (key == null) return ScriptJson.error("script not found: " + spec);
        if (key.startsWith(ScriptAssets.KIND_JS + ":")) {
            String name = key.substring(3);
            String source = ScriptAssets.get(ScriptAssets.KIND_JS, name);
            if (source == null) return ScriptJson.error("script not found: " + key);
            String err = JsRunner.load(key, source);
            if (err != null) return ScriptJson.error("js load failed: " + err);
            return JsRunner.call(key, func, argsJson, timeoutMs);
        }
        String name = key.substring(3);
        String source = ScriptAssets.get(ScriptAssets.KIND_PY, name);
        if (source == null) return ScriptJson.error("script not found: " + key);
        return PyRunner.call(name, source, func, ScriptJson.toArgs(argsJson));
    }

    /**
     * 把各种写法归一化成 "js:name" / "py:name"。
     *
     * @return 归一化后的 key，不是脚本标识或找不到时返回 null
     */
    public static String resolve(String spec) {
        String s = ScriptJson.safe(spec);
        if (s.length() == 0) return null;
        if (s.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(s);
                String kind = ScriptJson.optText(o, "engine", "kind", "type");
                String name = ScriptJson.optText(o, "script", "name", "file");
                if (kind.length() == 0) kind = ScriptAssets.KIND_JS;
                if (name.length() == 0) return null;
                if (name.endsWith(".js")) kind = ScriptAssets.KIND_JS;
                if (name.endsWith(".py")) kind = ScriptAssets.KIND_PY;
                return has(kind, stripExt(name)) ? kind + ":" + stripExt(name) : null;
            } catch (Throwable t) {
                return null;
            }
        }
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        s = s.trim();
        /* URL、绝对路径都不是脚本标识 */
        if (s.contains("://") || s.startsWith("/") || s.startsWith("file:")) return null;

        if (s.startsWith("js:") || s.startsWith("JS:")) {
            String name = s.substring(3);
            return has(ScriptAssets.KIND_JS, name) ? ScriptAssets.KIND_JS + ":" + name : null;
        }
        if (s.startsWith("py:") || s.startsWith("PY:")) {
            String name = s.substring(3);
            return has(ScriptAssets.KIND_PY, name) ? ScriptAssets.KIND_PY + ":" + name : null;
        }
        if (s.endsWith(".js")) {
            String name = s.substring(0, s.length() - 3);
            return has(ScriptAssets.KIND_JS, name) ? ScriptAssets.KIND_JS + ":" + name : null;
        }
        if (s.endsWith(".py")) {
            String name = s.substring(0, s.length() - 3);
            return has(ScriptAssets.KIND_PY, name) ? ScriptAssets.KIND_PY + ":" + name : null;
        }
        if (has(ScriptAssets.KIND_JS, s)) return ScriptAssets.KIND_JS + ":" + s;
        if (has(ScriptAssets.KIND_PY, s)) return ScriptAssets.KIND_PY + ":" + s;
        return null;
    }

    /** 是不是一个脚本标识（供 WebHome 判断 ext 到底是页面 URL 还是脚本） */
    public static boolean isScript(String spec) {
        return resolve(spec) != null;
    }

    /** 从 "js:demo?func=hello&args=[1,2]" 里取 func，没有则返回空串 */
    public static String queryFunc(String spec) {
        String s = ScriptJson.safe(spec);
        int q = s.indexOf('?');
        if (q < 0) return "";
        String query = s.substring(q + 1);
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            if ("func".equalsIgnoreCase(pair.substring(0, i).trim())) return pair.substring(i + 1).trim();
        }
        return "";
    }

    /**
     * 统一的 action 入口解析。
     * <ul>
     *   <li>"script:js:demo?func=hello" -> 显式指定脚本 + 函数</li>
     *   <li>"hello"                     -> 调用 defaultSpec 脚本的 action(name)</li>
     *   <li>""                          -> 调用 defaultSpec 脚本的 defaultFunc / main</li>
     * </ul>
     */
    public static String runAction(String action, String defaultSpec, String defaultFunc) {
        String a = ScriptJson.safe(action);
        if (a.startsWith("script:")) {
            String tail = a.substring("script:".length()).trim();
            String func = queryFunc(tail);
            String key = resolve(tail);
            if (key == null) key = defaultSpec;
            return call(key, func.length() > 0 ? func : "action", "[]");
        }
        if (a.length() > 0) return call(defaultSpec, "action", ScriptJson.array(a));
        String fn = ScriptJson.empty(defaultFunc) ? "main" : defaultFunc;
        return call(defaultSpec, fn, "[]");
    }

    public static boolean has(String kind, String name) {
        return ScriptAssets.get(kind, name) != null;
    }

    /** 已内置的脚本清单，方便排查打包是否生效 */
    public static String list() {
        JSONObject out = new JSONObject();
        try {
            JSONArray js = new JSONArray();
            for (String n : ScriptAssets.names(ScriptAssets.KIND_JS)) js.put(n);
            JSONArray py = new JSONArray();
            for (String n : ScriptAssets.names(ScriptAssets.KIND_PY)) py.put(n);
            out.put("js", js);
            out.put("py", py);
            out.put("pyAvailable", PyRunner.available());
        } catch (Throwable ignored) {
        }
        return out.toString();
    }

    private static String stripExt(String name) {
        String n = name == null ? "" : name.trim();
        if (n.endsWith(".js")) return n.substring(0, n.length() - 3);
        if (n.endsWith(".py")) return n.substring(0, n.length() - 3);
        return n;
    }
}
