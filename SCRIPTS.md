# 内置脚本（JS / PY）接入说明

把任意能在影视TV 独立运行的 `.js` / `.py` 打进 jar，用 `csp_` 调用，效果与直接配置脚本地址一致。

## 核心机制

**jar 不实现任何脚本引擎**，只做三件事：

1. 把内置脚本从 jar 释放成真实文件 → `filesDir/scripts/`
2. 反射宿主的 `PyLoader` / `JsLoader`，让它生成 Spider
3. `ScriptProxy` 把生命周期**全量转发**过去

因此脚本运行在宿主原本的环境里：

| 脚本依赖 | 谁提供 |
|---|---|
| py: `from base.spider import Spider` | 宿主 chaquo 模块 |
| py: `import requests` / `lxml` | 宿主打进的 Python 包 |
| js: `request()` / `pdfh()` / `pd()` | 宿主 QuickJS 运行时注入 |

**脚本零改造**，不需要改 import、不需要包一层函数。

## 目录

```
sdk/src/main/scripts/          ← 脚本扔这里（中文名、子目录都行）
├── 江苏名师课堂.js
├── 在线转本地_v6.0.py
└── manifest.json              ← 可选，指定生成类名
```

`manifest.json`（文件名含非 ASCII 时建议写）：

```json
{
  "江苏名师课堂.js":    { "class": "Jiangsu" },
  "在线转本地_v6.0.py": { "class": "OnlineToLocal" }
}
```

不写也行，类名会自动从文件名清洗出来。

## 编译

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash build.sh
```

编译期自动完成（无需人工干预）：

- `tools/gen_spider_proxy.py` 为每个脚本生成代理类到 `sdk/build/gen/`
- 脚本复制成 ASCII 名到 `sdk/build/res/scripts/`
- `jar uf` 把 `scripts/` 打进 jar

生成的类（以江苏名师课堂为例）：

| 文件 | 用途 |
|---|---|
| `Jiangsu.java` | 标准类，api 填 `csp_Jiangsu` |
| `csp_Jiangsu.java` | 兼容类，供「不剥离 csp_ 前缀」的壳使用 |

两个类都生成，api 都填 `csp_Jiangsu`，壳用哪种解析方式都能命中。

## 站点配置

```json
{ "key": "csp_Jiangsu", "name": "江苏名师课堂", "type": 3,
  "api": "csp_Jiangsu", "searchable": 1, "filterable": 1,
  "ext": {"我的课程": ["初中|初三|数学|苏科版|上册"]} }
```

**ext 原样透传，jar 完全不解析** —— 脚本原来需要什么 ext，这里就填什么，一个字都不用改。

## 直通模式

`scriptPath()` 返回远程地址或本地绝对路径时，不读 jar 资源，直接交给宿主 Loader：

```java
// 生成的类里改成这样就走直通
protected String scriptPath() { return "https://cdn.example.com/xxx.js"; }
```

`ScriptHost.prepare()` 自动识别：`http(s)://`、`file://`、`/` 或 `./` 开头 → 直通；否则 → 读 jar 内置资源。

## 交付地址模式

默认 `file://` + `filesDir`。若宿主不认，可在 `Init` 或 `init()` 里切换：

```java
ScriptHost.setMode(ScriptHost.MODE_RAW);   // 裸绝对路径
// 或走影视TV 本地 HTTP 服务
ScriptHost.setMode(ScriptHost.MODE_HTTP);
ScriptHost.setHttpBase("http://127.0.0.1:9978/file/TVBox/scripts/");
```

## 关于没有生命周期方法的脚本

`@tvbox-role manager` 这类脚本（如「在线转本地」）没有 `homeContent` 等方法。
jar 不做特殊处理 —— 宿主调什么就转发什么，行为由宿主 Loader 决定，与直接配置脚本地址时完全一致。

## 排障

| 现象 | 检查 |
|---|---|
| `script not in jar` | 编译日志里 `--- jar 内 /scripts/ ---` 是否列出该脚本 |
| `no host loader for ...` | 宿主类名对不上，改 `ScriptHost.loaderNames()` 里的候选类名 |
| 脚本 import 报错 | 宿主不是 py 版壳，或该包没打进去 |
| 页面空白 | logcat 过滤 `ScriptProxy` / `ScriptHost` |

## 注意

- 新增/删除脚本后必须重新 `bash build.sh`
- 脚本体积直接进 jar，越大加载越慢
- `sdk/build/` 是产物目录，已加入 `.gitignore`
