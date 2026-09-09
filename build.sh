#!/bin/bash
# build.sh - FongMi WebHome SDK 编译脚本
# 编译 Java -> class -> dex -> jar
# 输出: webhome.jar (可被 fongmi/catvod 壳 DexClassLoader 加载)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$SCRIPT_DIR"
SDK_DIR="$ROOT_DIR/sdk"
SRC_DIR="$SDK_DIR/src/main/java"
OUT_DIR="$SDK_DIR/build/classes"
DEX_DIR="$SDK_DIR/build/dex"
BUILD_DIR="$SDK_DIR/build"
ASSETS_SRC="$SDK_DIR/src/main/assets"
# 脚本烘焙目录：tools/gen_script_assets.py 把 assets/{js,py} 生成为 Java 常量类
GEN_DIR="$SDK_DIR/gen"
# 内置脚本：sdk/src/main/scripts 下的 js/py，自动生成 csp_ 代理类 + ASCII 化资源
SCRIPTS_SRC="$SDK_DIR/src/main/scripts"
PROXY_GEN_DIR="$BUILD_DIR/gen"
PROXY_RES_DIR="$BUILD_DIR/res"
OBF_DIR="$BUILD_DIR/obfuscated"
RULES="$SDK_DIR/proguard-rules.pro"

# 混淆开关：默认开；设 OBFUSCATE=0 可关闭
OBFUSCATE="${OBFUSCATE:-1}"
# 混淆工具：优先 R8（自带 dex 输出），其次 ProGuard
R8_JAR="${R8_JAR:-}"
PG_JAR="${PG_JAR:-}"
USE_R8=0
DID_OBFUSCATE=0

# 方案A：加密 dex 动态加载。SECURE=1 时 classes.dex 只留空壳，真实逻辑进 payload.bin
SECURE="${SECURE:-0}"
PAYLOAD_DIR="$BUILD_DIR/payloads"
SHELL_DIR="$BUILD_DIR/classes_shell"
DID_SECURE=0

# Android SDK
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
if [ -z "$ANDROID_SDK_ROOT" ]; then
    echo "ERROR: ANDROID_SDK_ROOT or ANDROID_HOME not set"
    exit 1
fi

# 优先用 android-34，备用 android-33
PLATFORM=""
BT=""
for ver in "android-34" "android-33" "android-30" "android-29"; do
    if [ -f "$ANDROID_SDK_ROOT/platforms/$ver/android.jar" ]; then
        PLATFORM="$ANDROID_SDK_ROOT/platforms/$ver/android.jar"
        break
    fi
done

# 选 build-tools
for ver in "34.0.0" "33.0.2" "32.0.0" "30.0.0"; do
    if [ -x "$ANDROID_SDK_ROOT/build-tools/$ver/d8" ]; then
        BT="$ANDROID_SDK_ROOT/build-tools/$ver"
        break
    fi
done

if [ -z "$PLATFORM" ]; then
    echo "ERROR: android.jar not found"
    echo "Run: sdkmanager \"platforms;android-34\""
    exit 1
fi

if [ -z "$BT" ]; then
    echo "ERROR: build-tools not found (no d8)"
    echo "Run: sdkmanager \"build-tools;34.0.0\""
    exit 1
fi

echo "Using PLATFORM=$PLATFORM"
echo "Using BT=$BT"
echo "Using SRC_DIR=$SRC_DIR"

echo ""
echo "=== 0. 烘焙内置脚本 (assets/js, assets/py) -> ScriptAssets.java ==="
mkdir -p "$GEN_DIR"
if command -v python3 >/dev/null 2>&1; then
    python3 "$ROOT_DIR/tools/gen_script_assets.py" --assets "$ASSETS_SRC" --out "$GEN_DIR/com/github/catvod/spider"
elif command -v python >/dev/null 2>&1; then
    python "$ROOT_DIR/tools/gen_script_assets.py" --assets "$ASSETS_SRC" --out "$GEN_DIR/com/github/catvod/spider"
else
    echo "!! 未找到 python3，跳过脚本烘焙（内置的 js/py 将不可用）"
    mkdir -p "$GEN_DIR/com/github/catvod/spider"
    if [ ! -f "$GEN_DIR/com/github/catvod/spider/ScriptAssets.java" ]; then
        echo "!! 且缺少 ScriptAssets.java，编译会失败；请先安装 python3 或手写该占位类"
        exit 1
    fi
fi

echo ""
echo "=== 0.5 生成内置脚本代理类 (sdk/src/main/scripts) ==="
# 每个 .js/.py 生成一个 csp_Xxx 代理类，ext 在运行时原样透传给宿主 Loader
if [ -d "$SCRIPTS_SRC" ]; then
    if command -v python3 >/dev/null 2>&1; then
        if [ "$SECURE" = "1" ]; then
            python3 "$ROOT_DIR/tools/gen_spider_proxy.py" --src "$SCRIPTS_SRC" \
                --gen "$PROXY_GEN_DIR" --res "$PROXY_RES_DIR" --secure || exit 1
        else
            python3 "$ROOT_DIR/tools/gen_spider_proxy.py" --src "$SCRIPTS_SRC" \
                --gen "$PROXY_GEN_DIR" --res "$PROXY_RES_DIR" || exit 1
        fi
    elif command -v python >/dev/null 2>&1; then
        if [ "$SECURE" = "1" ]; then
            python "$ROOT_DIR/tools/gen_spider_proxy.py" --src "$SCRIPTS_SRC" \
                --gen "$PROXY_GEN_DIR" --res "$PROXY_RES_DIR" --secure || exit 1
        else
            python "$ROOT_DIR/tools/gen_spider_proxy.py" --src "$SCRIPTS_SRC" \
                --gen "$PROXY_GEN_DIR" --res "$PROXY_RES_DIR" || exit 1
        fi
    else
        echo "!! 未找到 python3，跳过内置脚本（已生成的代理类仍会参与编译）"
    fi
fi

echo ""
echo "=== 1. 编译 Java 源码 ==="
mkdir -p "$OUT_DIR" "$DEX_DIR" "$BUILD_DIR/libs"
rm -rf "$OUT_DIR"/* "$DEX_DIR"/* 2>/dev/null || true

{ find "$SRC_DIR" -name "*.java"; [ -d "$GEN_DIR" ] && find "$GEN_DIR" -name "*.java"; [ -d "$PROXY_GEN_DIR" ] && find "$PROXY_GEN_DIR" -name "*.java"; } | sort > "$BUILD_DIR/sources.txt"
echo "Source files:"
cat "$BUILD_DIR/sources.txt"

javac -encoding UTF-8 -cp "$PLATFORM" -d "$OUT_DIR" @"$BUILD_DIR/sources.txt"

echo ""
echo "=== 2. 删除 stub Spider.class (壳自带真正的 Spider) ==="
rm -f "$OUT_DIR/com/github/catvod/crawler/Spider.class"
rm -f "$OUT_DIR/com/github/catvod/crawler/Spider\$*.class"
echo "Removed Spider stub class(es)"

echo ""
echo "=== 3. 编译后的 class 文件 ==="
find "$OUT_DIR" -name "*.class" | sort > "$BUILD_DIR/classes.txt"
cat "$BUILD_DIR/classes.txt"

echo ""
echo "=== 3.5 混淆 (R8 / ProGuard) ==="
if [ "$OBFUSCATE" != "1" ]; then
    echo "OBFUSCATE=0，跳过混淆"
else
    # 自动探测混淆工具
    if [ -z "$R8_JAR" ] && [ -f "$ROOT_DIR/tools/r8.jar" ]; then R8_JAR="$ROOT_DIR/tools/r8.jar"; fi
    if [ -z "$PG_JAR" ] && [ -f "$ROOT_DIR/tools/proguard.jar" ]; then PG_JAR="$ROOT_DIR/tools/proguard.jar"; fi
    if [ -z "$PG_JAR" ] && [ -f "$ANDROID_SDK_ROOT/tools/proguard/lib/proguard.jar" ]; then
        PG_JAR="$ANDROID_SDK_ROOT/tools/proguard/lib/proguard.jar"
    fi

    if [ -n "$R8_JAR" ] && [ -f "$R8_JAR" ]; then
        echo "Using R8=$R8_JAR"
        rm -rf "$DEX_DIR" "$OBF_DIR"
        mkdir -p "$DEX_DIR" "$OBF_DIR"
        java -jar "$R8_JAR" --release --lib "$PLATFORM" --min-api 24 \
            --pg-conf "$RULES" --output "$DEX_DIR" @"$BUILD_DIR/classes.txt"
        USE_R8=1
        DID_OBFUSCATE=1
    elif [ -n "$PG_JAR" ] && [ -f "$PG_JAR" ]; then
        echo "Using ProGuard=$PG_JAR"
        rm -rf "$OBF_DIR"
        mkdir -p "$OBF_DIR"
        cat > "$BUILD_DIR/proguard.pro" <<PGEOF
-injars      '$OUT_DIR'
-outjars     '$OBF_DIR'
-libraryjars '$PLATFORM'
PGEOF
        cat "$RULES" >> "$BUILD_DIR/proguard.pro"
        java -jar "$PG_JAR" @"$BUILD_DIR/proguard.pro"
        # 混淆后路径变了，重新生成 class 清单
        find "$OBF_DIR" -name "*.class" | sort > "$BUILD_DIR/classes.txt"
        DID_OBFUSCATE=1
    else
        echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
        echo "!! 未找到 R8 / ProGuard，跳过混淆（jar 可被直接反编译）"
        echo "!! 下载 R8 放到 tools/r8.jar，或：export R8_JAR=/path/r8.jar"
        echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
    fi
fi

echo ""
echo "=== 4. 转换为 dex ==="
if [ "$USE_R8" = "1" ]; then
    echo "R8 已直接输出 dex，跳过 d8"
else
$BT/d8 --release --lib "$PLATFORM" --min-api 24 --output "$DEX_DIR" @"$BUILD_DIR/classes.txt"
fi
ls -la "$DEX_DIR"

if [ ! -f "$DEX_DIR/classes.dex" ]; then
    echo "!!! ERROR: classes.dex not generated"
    exit 1
fi

if [ "$SECURE" = "1" ]; then
    echo ""
    echo "=== 4.5 加密完整 dex -> payload.bin ==="
    if command -v python3 >/dev/null 2>&1; then PY=python3; else PY=python; fi
    rm -rf "$PAYLOAD_DIR"; mkdir -p "$PAYLOAD_DIR"
    $PY "$ROOT_DIR/tools/pack_payload.py" \
        "$DEX_DIR/classes.dex" "$PAYLOAD_DIR/payload.bin" || exit 1

    echo ""
    echo "=== 4.6 二次编译：薄壳 dex（只含入口类，反编译看不到逻辑） ==="
    rm -rf "$SHELL_DIR"; mkdir -p "$SHELL_DIR"
    {
        echo "$SRC_DIR/com/github/catvod/spider/SecureSpider.java"
        [ -f "$PROXY_GEN_DIR/com/github/catvod/spider/ScriptIndex.java" ] && \
            echo "$PROXY_GEN_DIR/com/github/catvod/spider/ScriptIndex.java"
        find "$PROXY_GEN_DIR" -name "*.java" ! -name "*Script.java" ! -name "ScriptIndex.java"
    } | sort > "$BUILD_DIR/shell_sources.txt"
    echo "Shell source files:"
    cat "$BUILD_DIR/shell_sources.txt"

    javac -encoding UTF-8 -cp "$PLATFORM" -d "$SHELL_DIR" @"$BUILD_DIR/shell_sources.txt"
    rm -f "$SHELL_DIR/com/github/catvod/crawler/Spider.class"
    rm -f "$SHELL_DIR/com/github/catvod/crawler/Spider\$*.class"

    rm -rf "$DEX_DIR"; mkdir -p "$DEX_DIR"
    find "$SHELL_DIR" -name "*.class" | sort > "$BUILD_DIR/classes_shell.txt"
    $BT/d8 --release --lib "$PLATFORM" --min-api 24 --output "$DEX_DIR" @"$BUILD_DIR/classes_shell.txt"
    ls -la "$DEX_DIR"
    if [ ! -f "$DEX_DIR/classes.dex" ]; then
        echo "!!! ERROR: 薄壳 dex 生成失败"
        exit 1
    fi
    DID_SECURE=1
fi

echo ""
echo "=== 5. 打包 webhome.jar (含 dex) ==="
mkdir -p "$BUILD_DIR/META-INF"
printf "Manifest-Version: 1.0\nCreated-By: FongMi WebHome SDK Build 1.0\n" > "$BUILD_DIR/META-INF/MANIFEST.MF"

cd "$BUILD_DIR"
rm -f webhome.jar
jar cfm webhome.jar META-INF/MANIFEST.MF -C dex .

# 内置脚本（ASCII 化后）打进 jar 的 /scripts/，运行时由 ScriptHost 释放给宿主 Loader
if [ -d "$PROXY_RES_DIR" ]; then
    jar uf webhome.jar -C "$PROXY_RES_DIR" .
fi
# 加密的真实 dex（SECURE 模式）
if [ "$DID_SECURE" = "1" ] && [ -f "$PAYLOAD_DIR/payload.bin" ]; then
    jar uf webhome.jar -C "$PAYLOAD_DIR" payload.bin
fi
# WebHome 页内脚本常量（assets/{js,py}）也原样保留，便于反查
if [ -d "$ASSETS_SRC" ]; then
    jar uf webhome.jar -C "$ASSETS_SRC" .
fi
cd "$ROOT_DIR"

cp "$BUILD_DIR/webhome.jar" "$BUILD_DIR/libs/webhome.jar"

echo ""
echo "=== 6. 验证 jar 内容 ==="
echo "--- webhome.jar ---"
unzip -l "$BUILD_DIR/libs/webhome.jar"

echo ""
echo "=== 7. 验证 dex 内容 ==="
$BT/dexdump "$DEX_DIR/classes.dex" 2>/dev/null | grep "Class descriptor" | head -30

echo ""
echo "=== 7.1 内置脚本与 csp_ 入口 ==="
if [ -d "$SCRIPTS_SRC" ]; then
    (cd "$SCRIPTS_SRC" && find . -type f \( -name "*.js" -o -name "*.py" \) | sort)
fi
echo "--- jar 内 /scripts/ ---"
unzip -l webhome.jar 2>/dev/null | grep "scripts/" || true

echo ""
echo "=== 8. 输出 ==="
ls -la "$BUILD_DIR/libs/"
echo ""
echo "Build OK"

if [ "$DID_OBFUSCATE" = "1" ]; then
    echo "  混淆: 已启用"
else
    echo "  混淆: 未启用 —— jar 可被 jadx 直接还原，仅供自测"
fi
if [ "$DID_SECURE" = "1" ]; then
    echo "  加密dex: 已启用 —— classes.dex 只含空壳，逻辑在 payload.bin"
else
    echo "  加密dex: 未启用 —— 设 SECURE=1 开启"
fi

# md5：远程加载配置用 "spider":"http://xxx/webhome.jar;md5;<这行>"
if command -v md5sum >/dev/null 2>&1; then
    MD5=$(md5sum "$BUILD_DIR/libs/webhome.jar" | awk '{print $1}')
elif command -v md5 >/dev/null 2>&1; then
    MD5=$(md5 -q "$BUILD_DIR/libs/webhome.jar")
else
    MD5=""
fi
if [ -n "$MD5" ]; then
    echo "$MD5" > "$BUILD_DIR/libs/webhome.jar.md5"
    echo "  md5: $MD5"
fi
echo "  webhome.jar -> $BUILD_DIR/libs/webhome.jar"
