package com.github.catvod.spider;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PyRunner — 内置 PY 引擎。
 *
 * <p>Android 本身没有 Python，执行能力必须由宿主壳提供。这里按优先级探测：
 * <ol>
 *   <li>{@link #setEngine(Engine)} 注入的自定义引擎（壳或二次开发者提供）；</li>
 *   <li>Chaquopy（com.chaquo.python.Python），目前主流壳（FongMi / CatVod 系）
 *       内置的 Python 环境，通过反射调用，避免 SDK 编译期强依赖；</li>
 *   <li>外部进程 python3（默认关闭，{@link #setProcessFallback(boolean)} 打开，
 *       只在装了 Termux 之类环境的设备上可能可用）。</li>
 * </ol>
 *
 * <p>脚本源码在第一次调用时 exec 进一个独立的 globals 字典并缓存，
 * 之后每次调用都从这个字典里取函数，等价于模块级的 import 缓存。
 */
public final class PyRunner {

    private static final String TAG = "WebHomePy";

    /** 宿主实现的 Python 引擎，可用来替换内置的探测逻辑 */
    public interface Engine {
        /** @param args 参数列表（JSON 数组解析出来的 String/Number/Boolean/JSONObject 等） */
        String run(String name, String code, String func, Object[] args) throws Exception;
    }

    private static volatile Engine engine;
    private static volatile boolean processFallback;
    private static volatile String pythonCommand = "python3";
    private static volatile Context appContext;
    private static final Map<String, Object> GLOBALS = new ConcurrentHashMap<String, Object>();

    private PyRunner() {}

    public static void attach(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        appContext = app != null ? app : context;
    }

    public static void setEngine(Engine e) {
        engine = e;
    }

    public static void setProcessFallback(boolean enable) {
        processFallback = enable;
    }

    public static void setPythonCommand(String command) {
        if (command != null && command.length() > 0) pythonCommand = command;
    }

    /** 当前环境能不能跑 py（仅作提示，失败时 call 仍会返回 error JSON） */
    public static boolean available() {
        if (engine != null) return true;
        return chaquopy() != null;
    }

    public static String call(String name, String code, String func, Object[] args) {
        String fn = ScriptJson.empty(func) ? "main" : func.trim();
        Object[] argv = args == null ? new Object[0] : args;
        Engine custom = engine;
        if (custom != null) {
            try {
                return custom.run(name, code, fn, argv);
            } catch (Throwable t) {
                return ScriptJson.error("py engine error: " + t);
            }
        }
        try {
            Object globals = module(name, code);
            Object target = get(globals, fn);
            Object result = call(target, argv);
            return stringify(result);
        } catch (Throwable t) {
            Log.w(TAG, "chaquopy unavailable: " + t);
            if (processFallback) {
                String out = runProcess(name, code, fn, argv);
                if (out != null) return out;
            }
            return ScriptJson.error("no python runtime (chaquopy missing): " + t);
        }
    }

    /* ==================== Chaquopy 反射实现 ==================== */

    private static Object module(String name, String code) throws Exception {
        Object cached = GLOBALS.get(name);
        if (cached != null) return cached;
        synchronized (GLOBALS) {
            cached = GLOBALS.get(name);
            if (cached != null) return cached;
            Object py = chaquopy();
            if (py == null) throw new IllegalStateException("com.chaquo.python.Python not found");
            Object builtins = py.getClass().getMethod("getBuiltins").invoke(py);
            Object globals = call(get(builtins, "dict"));
            call(get(builtins, "exec"), code + "\n" + BOOT, globals);
            GLOBALS.put(name, globals);
            return globals;
        }
    }

    /** 紧跟在脚本后面执行：把 __exports 里的自定义入口展平到模块全局，便于按名调用 */
    private static final String BOOT =
            "\ntry:\n" +
            "    _wh_exports = __exports\n" +
            "    for _k, _v in _wh_exports.items():\n" +
            "        globals()[_k] = _v\nexcept NameError:\n" +
            "    pass\n";

    private static Object chaquopy() {
        try {
            Class<?> cls = Class.forName("com.chaquo.python.Python");
            Method m = cls.getMethod("getInstance");
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** PyObject.get(String) */
    private static Object get(Object obj, String key) throws Exception {
        return obj.getClass().getMethod("get", String.class).invoke(obj, key);
    }

    /** PyObject.call(Object... args) */
    private static Object call(Object obj, Object... args) throws Exception {
        Method m = obj.getClass().getMethod("call", Object[].class);
        return m.invoke(obj, new Object[]{args});
    }

    /** PyObject.callAttr(String name, Object... args) */
    private static Object callAttr(Object obj, String name, Object... args) throws Exception {
        Method m = obj.getClass().getMethod("callAttr", String.class, Object[].class);
        return m.invoke(obj, name, args);
    }

    /** 结果统一转成字符串：str 直接用，dict/list 走 json.dumps */
    private static String stringify(Object result) {
        if (result == null) return "";
        if (result instanceof String) return (String) result;
        try {
            Object py = chaquopy();
            if (py != null) {
                Object json = py.getClass().getMethod("getModule", String.class).invoke(py, "json");
                Object dumped = callAttr(json, "dumps", result);
                if (dumped != null) return dumped.toString();
            }
        } catch (Throwable ignored) {
        }
        return result.toString();
    }

    /* ==================== 外部进程兜底 ==================== */

    private static String runProcess(String name, String code, String func, Object[] args) {
        File file = null;
        try {
            Context ctx = appContext;
            if (ctx == null) return null;
            File dir = new File(ctx.getCacheDir(), "webhome_py");
            if (!dir.exists()) dir.mkdirs();
            file = new File(dir, name + ".py");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(code.getBytes("UTF-8"));
            fos.write(("\n\nimport json,sys\n"
                    + "def __wh_main():\n"
                    + "    _args = json.loads(sys.argv[1])\n"
                    + "    _fn = globals().get(" + pyStr(func) + ")\n"
                    + "    if _fn is None: print(json.dumps({'error':'function not found: " + func + "'})); return\n"
                    + "    _r = _fn(*_args)\n"
                    + "    print(_r if isinstance(_r, str) else json.dumps(_r))\n"
                    + "__wh_main()\n").getBytes("UTF-8"));
            fos.flush();
            fos.close();

            ProcessBuilder pb = new ProcessBuilder(pythonCommand, file.getAbsolutePath(), toJsonArray(args));
            pb.directory(dir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.InputStream is = p.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            is.close();
            p.waitFor();
            String out = new String(bos.toByteArray(), "UTF-8").trim();
            return out.length() == 0 ? null : out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (file != null) {
                try {
                    file.delete();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String pyStr(String s) {
        return "'" + String.valueOf(s).replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String toJsonArray(Object[] args) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (Object a : args) {
            if (a == null) arr.put(org.json.JSONObject.NULL);
            else arr.put(a);
        }
        return arr.toString();
    }
}
