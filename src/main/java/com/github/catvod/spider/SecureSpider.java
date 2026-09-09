package com.github.catvod.spider;

import android.content.Context;
import android.util.Log;

import com.github.catvod.crawler.Spider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SecureSpider — 加密 dex 动态加载壳（SECURE 模式入口基类）。
 *
 * <p> jar 里的 classes.dex 只包含这类薄壳，真实逻辑全部在加密的 payload 里：
 * <pre>
 *   jar/
 *   ├── classes.dex     ← 只有 SecureSpider + 各 csp_ 入口壳（可被反编译，但无内容）
 *   ├── payload.bin     ← 加密的真实 dex（ScriptProxy / ScriptHost / 真实爬虫…）
 *   └── scripts/        ← 内置脚本
 * </pre>
 *
 * <p><b>最关键的一点</b>：构造 DexClassLoader 时 parent 必须传
 * {@code getClass().getClassLoader()}（壳给的 loader）。传 null 的话
 * payload 里的类找不到 com.github.catvod.crawler.Spider，会直接 NoClassDefFoundError。
 *
 * <p><b>防护等级</b>：防静态解压（jadx 打开 classes.dex 只能看到空壳）。
 * payload 解密后要落盘才能加载，防不住 root 后捞文件。别把密钥硬编码在客户端指望真保密。
 */
public abstract class SecureSpider extends Spider {

    private static final String TAG = "SecureSpider";

    private static final String PAYLOAD = "/payload.bin";
    private static final byte[] MAGIC = {0x57, 0x48, 0x50, 0x31}; /* WHP1 */
    private static final String SEED = "webhome-payload-v1";

    /** 进程内只解一次，避免每个站点都重复解密加载 */
    private static volatile ClassLoader payloadLoader;
    private static volatile String payloadError = "";

    /** 子类返回 payload 里的真实类名 */
    protected abstract String target();

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
            ClassLoader loader = ensureLoader(context);
            Class<?> real = Class.forName(target(), true, loader);
            Object obj = real.newInstance();
            /* Spider 由 parent（壳 loader）加载，payload 与壳看到的是同一个 Class，可安全强转 */
            delegate = (Spider) obj;
            delegate.init(context, str);
            error = "";
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage());
            Log.e(TAG, "init failed: " + target(), t);
        }
    }

    private static ClassLoader ensureLoader(Context context) throws Exception {
        ClassLoader cached = payloadLoader;
        if (cached != null) return cached;
        synchronized (SecureSpider.class) {
            if (payloadLoader != null) return payloadLoader;
            byte[] raw = readResource(PAYLOAD);
            if (raw == null) throw new IllegalStateException("payload.bin not in jar");
            byte[] dex = decode(raw);

            File dir = new File(context.getFilesDir(), "sec");
            if (!dir.exists()) dir.mkdirs();

            /* 解密后的 dex 需要落盘，DexClassLoader 只认文件路径 */
            File dexFile = new File(dir, "payload.dex");
            if (!dexFile.exists() || dexFile.length() != dex.length) {
                writeFile(dexFile, dex);
            }
            /* jar 里的脚本也要预释放：payload 里的 ScriptHost 由 DexClassLoader 加载，
               它的 getResourceAsStream 查的是 payload.dex 所在目录，拿不到 jar 内的 scripts/ */
            dumpScripts(context);

            File opt = new File(context.getCodeCacheDir(), "sec_opt");
            if (!opt.exists()) opt.mkdirs();

            payloadLoader = new dalvik.system.DexClassLoader(
                    dexFile.getAbsolutePath(),
                    opt.getAbsolutePath(),
                    null,
                    SecureSpider.class.getClassLoader());   /* ← parent 必须是壳的 loader */
            return payloadLoader;
        }
    }

    /** 把 jar 内的 scripts/ 释放到 filesDir/scripts/，供 payload 里的 ScriptHost 读取 */
    private static void dumpScripts(Context context) {
        String[] paths;
        try {
            paths = ScriptIndex.PATHS;
        } catch (Throwable t) {
            return;
        }
        for (String p : paths) {
            try {
                File out = new File(context.getFilesDir(), p);
                if (out.exists() && out.length() > 0) continue;
                byte[] data = readResource("/" + p);
                if (data == null) continue;
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                writeFile(out, data);
            } catch (Throwable ignored) {
            }
        }
    }

    private Spider d() {
        return delegate;
    }

    private String fail(String what) {
        Log.w(TAG, "delegate missing at " + what + " (" + target() + ")"
                + (error.length() > 0 ? ": " + error : "")
                + (payloadError.length() > 0 ? " / " + payloadError : ""));
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

    /* ==================== payload 加解密 ====================
     * 算法与 tools/pack_payload.py 严格一致：MAGIC(4B) + XOR(key[i % 32])
     * 明文 dex（无 MAGIC 头）也能直接加载，方便调试。
     */

    private static byte[] decode(byte[] src) {
        if (src == null || src.length < MAGIC.length) return src;
        for (int i = 0; i < MAGIC.length; i++) {
            if (src[i] != MAGIC[i]) return src;
        }
        byte[] key = seedKey();
        byte[] out = new byte[src.length - MAGIC.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (src[i + MAGIC.length] ^ key[i % key.length]);
        }
        return out;
    }

    private static byte[] seedKey() {
        String seed = SEED + SecureSpider.class.getName();
        byte[] raw;
        try {
            raw = seed.getBytes("UTF-8");
        } catch (Throwable t) {
            raw = seed.getBytes();
        }
        byte[] k = new byte[32];
        int acc = 0x5A;
        for (int i = 0; i < k.length; i++) {
            int v = raw[i % raw.length] & 0xFF;
            acc = (acc * 31 + v) & 0xFF;
            k[i] = (byte) (v ^ acc);
        }
        return k;
    }

    /* ==================== IO ==================== */

    private static byte[] readResource(String path) {
        InputStream in = null;
        try {
            in = SecureSpider.class.getResourceAsStream(path);
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            close(in);
        }
    }

    private static void writeFile(File f, byte[] data) throws Exception {
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
            out.flush();
        } finally {
            close(out);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
