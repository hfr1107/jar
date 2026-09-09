package com.github.catvod.spider;

import android.content.Context;
import android.util.Log;

import com.github.catvod.crawler.Spider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * ScriptHost — 把 jar 内置的 js / py 脚本交给「宿主自己的 Loader」去执行。
 *
 * <p>这是整个方案的关键：jar 不自己实现任何脚本引擎，而是
 * <ol>
 *   <li>把内置脚本从 jar 资源释放成真实文件（宿主 Loader 只认文件路径/URL）；</li>
 *   <li>反射找到宿主的 PyLoader / JsLoader，让它生成 Spider；</li>
 *   <li>由 {@link ScriptProxy} 把生命周期原样转发过去。</li>
 * </ol>
 *
 * 这样做的好处是脚本跑在宿主原本的运行环境里：
 * py 脚本的 <code>from base.spider import Spider</code>、<code>import requests</code>、
 * js 脚本的 <code>request()</code> / <code>pdfh()</code> 等注入能力全部原样可用，
 * 脚本不需要任何改造。
 *
 * <p>ext 参数在整条链路上<b>不做任何解析</b>，原样透传给宿主 Loader，
 * 因此脚本拿到的 ext 和直接配置 <code>"api":"./py/xxx.py"</code> 时完全一致。
 */
public final class ScriptHost {

    private static final String TAG = "ScriptHost";

    /** 内置脚本在 jar 里的根目录 */
    private static final String RES_ROOT = "/scripts/";

    /**
     * 交付给宿主 Loader 的地址形式。
     * FILE  : file:///data/data/pkg/files/scripts/xxx.py
     * RAW   : /data/data/pkg/files/scripts/xxx.py
     * HTTP  : 走影视TV 本地 HTTP 服务（需自行 setHttpBase 确认映射）
     */
    public static final int MODE_FILE = 0;
    public static final int MODE_RAW = 1;
    public static final int MODE_HTTP = 2;

    private static volatile int mode = MODE_FILE;
    private static volatile String httpBase = "";

    private ScriptHost() {}

    public static void setMode(int m) {
        mode = m;
    }

    /** HTTP 模式下脚本 URL 的前缀，例如 http://127.0.0.1:9978/file/TVBox/scripts/ */
    public static void setHttpBase(String base) {
        httpBase = base == null ? "" : base.trim();
    }

    /**
     * 准备好脚本地址。
     *
     * @param path 内置脚本相对路径（js/xxx.js、py/xxx.py），
     *             或直通地址（http(s)://、file://、./、/ 开头）
     * @return 交给宿主 Loader 的 api 字符串
     */
    public static String prepare(Context context, String path) {
        String raw = path == null ? "" : path.trim();
        if (raw.length() == 0) throw new IllegalStateException("empty script path");
        /* 直通：远程地址或本地绝对路径，不碰 jar 资源 */
        if (isRemote(raw) || raw.startsWith("file://") || raw.startsWith("/") || raw.startsWith("./")) {
            return raw;
        }
        return dump(context, raw);
    }

    private static boolean isRemote(String s) {
        return s.startsWith("http://") || s.startsWith("https://") || s.startsWith("ftp://");
    }

    /** 把 jar 内置脚本释放到 filesDir，返回可交给 Loader 的地址 */
    private static String dump(Context context, String rel) {
        InputStream in = null;
        OutputStream out = null;
        try {
            File dir = new File(context.getFilesDir(), "scripts");
            File target = new File(dir, rel);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            byte[] bytes = null;
            in = ScriptHost.class.getResourceAsStream(RES_ROOT + rel);
            if (in != null) {
                bytes = readAll(in);
                close(in);
                in = null;
            } else if (target.exists()) {
                /* SECURE 模式：脚本已由 SecureSpider 预释放到 filesDir。
                   payload 里的 ScriptHost 由 DexClassLoader 加载，
                   它的 getResourceAsStream 查的是 payload.dex 所在目录，拿不到 jar 内的 scripts/ */
                bytes = readFile(target);
            } else {
                throw new IllegalStateException("script not in jar: " + RES_ROOT + rel);
            }
            /* 加密过的脚本带 MAGIC 头，自动解密；明文脚本原样使用 */
            bytes = decode(bytes);
            /* 内容一致就跳过写入，避免每次进站点都重刷一遍 */
            if (!target.exists() || target.length() != bytes.length) {
                out = new FileOutputStream(target);
                out.write(bytes);
                out.flush();
            }
            if (mode == MODE_HTTP && httpBase.length() > 0) {
                return httpBase + rel;
            }
            String abs = target.getAbsolutePath();
            return mode == MODE_RAW ? abs : "file://" + abs;
        } catch (Throwable t) {
            throw new IllegalStateException("dump script failed: " + rel + " -> " + t, t);
        } finally {
            close(in);
            close(out);
        }
    }

    /**
     * 用宿主自己的 Loader 生成 Spider。
     *
     * @param api 脚本地址（prepare 的返回值）
     * @param ext 站点 ext，<b>原样透传，不解析</b>
     */
    public static Spider spider(String api, String ext) {
        Throwable last = null;
        for (String name : loaderNames(api)) {
            try {
                Class<?> cls = Class.forName(name);
                Method m = findGetSpider(cls);
                if (m == null) continue;
                Object target = Modifier.isStatic(m.getModifiers()) ? null : newInstance(cls);
                int n = m.getParameterTypes().length;
                Object[] args;
                if (n >= 4) args = new Object[]{"script", api, ext, null};
                else if (n == 3) args = new Object[]{"script", api, ext};
                else if (n == 2) args = new Object[]{api, ext};
                else continue;
                Object r = m.invoke(target, args);
                if (r instanceof Spider) {
                    Log.d(TAG, "loaded via " + name + " -> " + api);
                    return (Spider) r;
                }
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new IllegalStateException("no host loader for " + api
                + (last == null ? "" : " / " + last), last);
    }

    /** py 走 PyLoader，js 走 JsLoader；识别不了就都试一遍 */
    private static String[] loaderNames(String api) {
        String lower = api == null ? "" : api.toLowerCase();
        String[] py = {
                "com.fongmi.android.tv.api.loader.PyLoader",
                "com.fongmi.android.tv.api.loader.BaseLoader",
        };
        String[] js = {
                "com.fongmi.android.tv.api.loader.JsLoader",
                "com.fongmi.android.tv.api.loader.BaseLoader",
        };
        if (lower.contains(".py")) return py;
        if (lower.contains(".js")) return js;
        String[] all = new String[py.length + js.length];
        System.arraycopy(js, 0, all, 0, js.length);
        System.arraycopy(py, 0, all, js.length, py.length);
        return all;
    }

    /** 找 getSpider 方法：名字匹配、参数全是 String，取参数最多的那个 */
    private static Method findGetSpider(Class<?> cls) {
        Method best = null;
        Method[] all = cls.getMethods();
        for (Method m : all) {
            if (!"getSpider".equals(m.getName())) continue;
            Class<?>[] ps = m.getParameterTypes();
            boolean allString = true;
            for (Class<?> p : ps) {
                if (p != String.class) { allString = false; break; }
            }
            if (!allString) continue;
            if (best == null || ps.length > best.getParameterTypes().length) best = m;
        }
        if (best != null) best.setAccessible(true);
        return best;
    }

    private static Object newInstance(Class<?> cls) throws Exception {
        try {
            return cls.newInstance();
        } catch (Throwable t) {
            Method get = cls.getMethod("getInstance");
            return get.invoke(null);
        }
    }

    /* ==================== 内置脚本加密（可选） ====================
     *
     * 目的：脚本明文直接躺在 jar 里，解压即可拿到。加密后至少挡住
     * "解压 jar 直接看源码" 这一层。
     *
     * 诚实说明：运行时必须还原成明文文件交给宿主 Loader，所以这只能
     * 防静态扒取，防不住 root 后从 filesDir 捞。真要保密请把逻辑
     * 写进 Java 层而不是脚本。
     *
     * 由 tools/encrypt_scripts.py 生成，算法必须与之严格一致。
     */

    private static final byte[] MAGIC = {0x57, 0x48, 0x53, 0x31}; /* WHS1 */
    private static final String SEED = "webhome-script-v1";

    private static byte[] decode(byte[] src) {
        if (src == null || src.length < MAGIC.length) return src;
        for (int i = 0; i < MAGIC.length; i++) {
            if (src[i] != MAGIC[i]) return src;   /* 非加密，原样返回 */
        }
        byte[] key = seedKey();
        byte[] out = new byte[src.length - MAGIC.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (src[i + MAGIC.length] ^ key[i % key.length]);
        }
        return out;
    }

    private static byte[] seedKey() {
        String seed = SEED + ScriptHost.class.getName();
        byte[] raw;
        try {
            raw = seed.getBytes("UTF-8");
        } catch (Throwable t) {
            raw = seed.getBytes();
        }
        /* 简单扩散，避免密钥就是可读字符串 */
        byte[] k = new byte[32];
        int acc = 0x5A;
        for (int i = 0; i < k.length; i++) {
            int v = raw[i % raw.length] & 0xFF;
            acc = (acc * 31 + v) & 0xFF;
            k[i] = (byte) (v ^ acc);
        }
        return k;
    }

    private static byte[] readFile(File f) throws Exception {
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        try {
            return readAll(fis);
        } finally {
            close(fis);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
