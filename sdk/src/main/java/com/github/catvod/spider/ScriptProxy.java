package com.github.catvod.spider;

import android.content.Context;
import android.util.Log;

import com.github.catvod.crawler.Spider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ScriptProxy — 内置脚本 Spider 的基类。
 *
 * <p>每个内置脚本由 tools/gen_spider_proxy.py 自动生成一个子类，
 * 子类只需要实现 {@link #scriptPath()}：
 * <pre>
 *   public class Jiangsu extends ScriptProxy {
 *       protected String scriptPath() { return "js/jiangsu.js"; }
 *   }
 * </pre>
 * 站点里 api 写 <code>csp_Jiangsu</code>，ext 写脚本原本需要的任何内容。
 *
 * <p>两个关键约定：
 * <ol>
 *   <li><b>ext 零解析</b>：{@link #init(Context, String)} 收到什么就原样交给宿主
 *       Loader，脚本看到的 ext 和直接配置脚本地址时一模一样；</li>
 *   <li><b>生命周期全量转发</b>：不做任何取舍，宿主调哪个就转哪个，
 *       包括没有 homeContent 的 manager 类脚本（宿主自己决定行为）。</li>
 * </ol>
 */
public abstract class ScriptProxy extends Spider {

    private static final String TAG = "ScriptProxy";

    /** 内置脚本相对路径（js/xxx.js、py/xxx.py）；也可以是 http(s)://、file:// 直通地址 */
    protected abstract String scriptPath();

    private volatile Spider delegate;
    private volatile String error = "";

    @Override
    public void init(Context context) {
        init(context, "");
    }

    @Override
    public void init(Context context, String str) {
        super.init(context, str);
        try {
            String api = ScriptHost.prepare(context, scriptPath());
            delegate = ScriptHost.spider(api, str);
            error = "";
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage());
            Log.e(TAG, "init failed: " + scriptPath(), t);
        }
    }

    private Spider d() {
        return delegate;
    }

    private String fail(String what) {
        Log.w(TAG, "delegate missing at " + what + " (" + scriptPath() + ")"
                + (error.length() > 0 ? ": " + error : ""));
        return "";
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        Spider s = d();
        return s == null ? fail("homeContent") : s.homeContent(filter);
    }

    @Override
    public String homeVideoContent() throws Exception {
        Spider s = d();
        return s == null ? fail("homeVideoContent") : s.homeVideoContent();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        Spider s = d();
        return s == null ? fail("categoryContent") : s.categoryContent(tid, pg, filter, extend);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        Spider s = d();
        return s == null ? fail("detailContent") : s.detailContent(ids);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        Spider s = d();
        return s == null ? fail("searchContent") : s.searchContent(key, quick);
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        Spider s = d();
        return s == null ? fail("searchContent(pg)") : s.searchContent(key, quick, pg);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        Spider s = d();
        return s == null ? fail("playerContent") : s.playerContent(flag, id, vipFlags);
    }

    @Override
    public String liveContent(String url) throws Exception {
        Spider s = d();
        return s == null ? fail("liveContent") : s.liveContent(url);
    }

    @Override
    public boolean manualVideoCheck() throws Exception {
        Spider s = d();
        return s != null && s.manualVideoCheck();
    }

    @Override
    public boolean isVideoFormat(String url) throws Exception {
        Spider s = d();
        return s != null && s.isVideoFormat(url);
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        Spider s = d();
        return s == null ? null : s.proxy(params);
    }

    @Override
    public String action(String action) throws Exception {
        Spider s = d();
        return s == null ? fail("action") : s.action(action);
    }

    @Override
    public void destroy() {
        Spider s = d();
        if (s != null) {
            try {
                s.destroy();
            } catch (Throwable ignored) {
            }
        }
        delegate = null;
        super.destroy();
    }
}
