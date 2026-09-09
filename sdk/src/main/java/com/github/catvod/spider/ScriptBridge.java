package com.github.catvod.spider;

import android.webkit.JavascriptInterface;

/**
 * ScriptBridge — 注入脚本 WebView 的同步 JS 接口（_scriptBridge）。
 *
 * <p>内置脚本运行在隐藏 WebView 里，这里给它提供同步能力：
 * <pre>
 *   fm.req(url, options)             -> "{ok,status,body,url}" 走 Native HttpURLConnection
 *   fm.res(url, options)             -> 图片资源 URL（直连/代理线路分流）
 *   fm.log(msg)                      -> logcat，tag = WebHomeScript
 *   fm.script(spec, func, argsArray) -> 调用另一个内置脚本
 * </pre>
 *
 * 这里的方法在 WebView 的 JavaBridge 线程执行，JS 侧调用是同步阻塞的，
 * 所以脚本里可以直接 var r = fm.req(...)，不需要 await。
 */
public final class ScriptBridge {

    private ScriptBridge() {}

    @JavascriptInterface
    public String req(String url, String options) {
        try {
            return WebHome.doNativeReq(url, options == null ? "" : options);
        } catch (Throwable t) {
            return "{\"ok\":false,\"status\":0,\"error\":" + ScriptJson.js(String.valueOf(t.getMessage())) + "}";
        }
    }

    @JavascriptInterface
    public String res(String url, String options) {
        try {
            return WebHome.resolveResourceUrl(url, options == null ? "" : options);
        } catch (Throwable t) {
            return url == null ? "" : url;
        }
    }

    @JavascriptInterface
    public String log(String message) {
        try {
            android.util.Log.d("WebHomeScript", String.valueOf(message));
        } catch (Throwable ignored) {
        }
        return "";
    }

    @JavascriptInterface
    public String script(String spec, String func, String args) {
        return ScriptEngine.call(spec, func, args == null ? "[]" : args);
    }
}
