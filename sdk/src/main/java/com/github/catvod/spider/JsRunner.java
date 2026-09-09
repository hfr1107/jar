package com.github.catvod.spider;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONTokener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JsRunner — 内置 JS 引擎。
 *
 * <p>Android 上没有系统级 JS 引擎可用（Rhino 需要额外打进 dex，QuickJS 需要
 * so 库），但一定有 WebView。所以这里用一个隐藏的 WebView 当 JS 沙箱：
 * 脚本在这里 eval 一次，之后每次调用都是一次 evaluateJavascript。
 *
 * <p>特点：
 * <ul>
 *   <li>每个脚本被包进独立函数作用域，互不污染，函数名冲突不影响；</li>
 *   <li>支持 ES6+（Chromium 版本取决于系统 WebView）；</li>
 *   <li>通过 {@link ScriptBridge} 提供同步的 fm.req / fm.res / fm.log；</li>
 *   <li>调用是同步阻塞的（CountDownLatch），所以只能在子线程调用，
 *       主线程调用会直接返回错误，避免死锁。</li>
 * </ul>
 */
public final class JsRunner {

    private static final String TAG = "WebHomeJs";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final int READY_TIMEOUT = 8000;

    private static volatile Context appContext;
    private static volatile WebView web;
    private static volatile boolean ready;
    private static volatile boolean started;

    private JsRunner() {}

    /** 只保存 context，WebView 等到第一次真正调用脚本时才创建，零启动开销 */
    public static void attach(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        appContext = app != null ? app : context;
    }

    /**
     * 装载脚本（同一个 key 只装载一次）。
     *
     * @return null 表示成功，否则返回错误信息
     */
    public static String load(final String key, final String source) {
        if (ScriptJson.empty(key)) return "empty script key";
        if (source == null) return "null script source: " + key;
        if (!prepare()) return "js webview not ready";
        String js = "(function(){\n"
                + "try{\n"
                + "  window.__wh_scripts = window.__wh_scripts || {};\n"
                + "  if (window.__wh_scripts[" + ScriptJson.js(key) + "]) return 'ok';\n"
                + "  var __code = " + ScriptJson.js(source) + ";\n"
                + "  var __fn = new Function(__code + \"\\n;\" + " + ScriptJson.js(COLLECTOR) + ");\n"
                + "  window.__wh_scripts[" + ScriptJson.js(key) + "] = __fn();\n"
                + "  return 'ok';\n"
                + "}catch(e){ return String((e && e.message) || e); }\n"
                + "})()";
        String out = eval(js, 15000);
        if (out == null || out.length() == 0 || "ok".equals(out)) return null;
        return out;
    }

    /** 调用 key 对应脚本里的 func，argsJson 是 JSON 数组字符串 */
    public static String call(String key, String func, String argsJson) {
        return call(key, func, argsJson, 20000);
    }

    public static String call(String key, String func, String argsJson, int timeoutMs) {
        if (ScriptJson.empty(key)) return ScriptJson.error("empty script key");
        if (!prepare()) return ScriptJson.error("js webview not ready");
        String args = ScriptJson.empty(argsJson) ? "[]" : argsJson;
        String expr = "window.__wh_call(" + ScriptJson.js(key) + ","
                + ScriptJson.js(ScriptJson.empty(func) ? "main" : func) + ","
                + ScriptJson.js(args) + ")";
        return eval(expr, timeoutMs);
    }

    /** 释放隐藏 WebView（一般在壳回收 Spider 时调用，可不调） */
    public static void release() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                synchronized (LOCK) {
                    ready = false;
                    started = false;
                }
                if (web != null) {
                    try {
                        web.removeJavascriptInterface("_scriptBridge");
                        web.stopLoading();
                        web.loadUrl("about:blank");
                        web.destroy();
                    } catch (Throwable ignored) {
                    }
                    web = null;
                }
            }
        });
    }

    /* ==================== 内部实现 ==================== */

    /** 包在脚本后面执行，负责把脚本作用域里的函数收集出来 */
    private static final String COLLECTOR =
            "return (function(){\n" +
            "  var R = {};\n" +
            "  var names = ['homeContent','homeVideoContent','categoryContent','detailContent'," +
            "'searchContent','playerContent','liveContent','action','run','main','init','destroy'];\n" +
            "  for (var i = 0; i < names.length; i++) {\n" +
            "    try { var f = eval('(' + names[i] + ')'); if (typeof f === 'function') R[names[i]] = f; } catch(e) {}\n" +
            "  }\n" +
            "  try {\n" +
            "    if (typeof __exports === 'object' && __exports !== null) {\n" +
            "      for (var k in __exports) { if (typeof __exports[k] === 'function') R[k] = __exports[k]; }\n" +
            "    }\n" +
            "  } catch(e) {}\n" +
            "  return R;\n" +
            "})();";

    /** 页面初始化时注入的 fm 环境与调度入口 */
    private static final String RUNTIME =
            "window.__wh_scripts = window.__wh_scripts || {};\n" +
            "window.__wh_call = function(key, name, argsJson) {\n" +
            "  try {\n" +
            "    var m = (window.__wh_scripts || {})[key];\n" +
            "    if (!m) return JSON.stringify({error: 'script not loaded: ' + key});\n" +
            "    var fn = m[name];\n" +
            "    if (typeof fn !== 'function') return JSON.stringify({error: 'function not found: ' + name + ' in ' + key});\n" +
            "    var args = [];\n" +
            "    try { args = JSON.parse(argsJson || '[]'); } catch(e) { args = []; }\n" +
            "    if (Object.prototype.toString.call(args) !== '[object Array]') args = [args];\n" +
            "    var r = fn.apply(null, args);\n" +
            "    if (r === undefined || r === null) return '';\n" +
            "    return (typeof r === 'string') ? r : JSON.stringify(r);\n" +
            "  } catch(e) { return JSON.stringify({error: String((e && e.message) || e)}); }\n" +
            "};\n" +
            "if (!window.fm) {\n" +
            "  window.fm = {\n" +
            "    req: function(u, o) { return _scriptBridge.req(u, JSON.stringify(o || {})); },\n" +
            "    res: function(u, o) { return _scriptBridge.res(u, JSON.stringify(o || {})); },\n" +
            "    log: function(m) { return _scriptBridge.log(String(m)); },\n" +
            "    script: function(s, f, a) { return _scriptBridge.script(s, f || '', JSON.stringify(a || [])); }\n" +
            "  };\n" +
            "}\n";

    private static boolean prepare() {
        if (ready && web != null) return true;
        if (appContext == null) {
            Log.w(TAG, "attach(context) 未调用，拿不到 context");
            return false;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            create();
        } else {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    create();
                }
            });
        }
        long deadline = System.currentTimeMillis() + READY_TIMEOUT;
        synchronized (LOCK) {
            while (!ready && System.currentTimeMillis() < deadline) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                try {
                    LOCK.wait(Math.min(remain, 200L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return ready && web != null;
    }

    private static void markReady() {
        synchronized (LOCK) {
            ready = true;
            LOCK.notifyAll();
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private static void create() {
        if (web != null) return;
        if (started) return;
        started = true;
        try {
            WebView v = new WebView(appContext);
            WebSettings s = v.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setAllowFileAccess(true);
            s.setAllowContentAccess(true);
            try {
                s.setAllowUniversalAccessFromFileURLs(true);
            } catch (Throwable ignored) {
            }
            v.addJavascriptInterface(new ScriptBridge(), "_scriptBridge");
            v.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(final WebView view, String url) {
                    /*
                     * RUNTIME 注入完成后才能算 ready：否则第一次调用脚本时
                     * window.__wh_call 可能还没定义。回调 + 延迟双保险，
                     * 避免个别 ROM 不回调导致调用方干等 8 秒。
                     */
                    try {
                        view.evaluateJavascript(RUNTIME, new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                markReady();
                            }
                        });
                    } catch (Throwable ignored) {
                        markReady();
                    }
                    MAIN.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            markReady();
                        }
                    }, 1200L);
                }
            });
            v.loadDataWithBaseURL("http://127.0.0.1/", "<html><body></body></html>", "text/html", "utf-8", null);
            web = v;
        } catch (Throwable t) {
            Log.e(TAG, "create webview failed", t);
            synchronized (LOCK) {
                started = false;
                LOCK.notifyAll();
            }
        }
    }

    /** 同步执行一段 JS 表达式，返回值是字符串（对象由外层 JSON.stringify 处理） */
    private static String eval(String expression, int timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return ScriptJson.error("js engine cannot run on main thread");
        }
        final WebView v = web;
        if (v == null) return ScriptJson.error("js webview not ready");
        final AtomicReference<String> ref = new AtomicReference<String>();
        final CountDownLatch latch = new CountDownLatch(1);
        final String wrapped = "(function(){try{var r=(" + expression
                + ");return (typeof r === 'string') ? r : JSON.stringify(r === undefined ? null : r);"
                + "}catch(e){return JSON.stringify({error:String((e && e.message)||e)});}})()";
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    v.evaluateJavascript(wrapped, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            ref.set(value);
                            latch.countDown();
                        }
                    });
                } catch (Throwable t) {
                    ref.set(ScriptJson.error(String.valueOf(t.getMessage())));
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(timeoutMs <= 0 ? 20000 : timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return decode(ref.get());
    }

    /** evaluateJavascript 回调回来的是 JSON 编码值，字符串会带引号，这里还原 */
    private static String decode(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() == 0 || "null".equals(s)) return "";
        if (s.charAt(0) == '"') {
            try {
                Object o = new JSONTokener(s).nextValue();
                return o == null ? "" : o.toString();
            } catch (Throwable t) {
                return s;
            }
        }
        return s;
    }
}
