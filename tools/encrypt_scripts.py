#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
加密 sdk/build/res/scripts/ 下的脚本，防止解压 jar 直接看到明文。

用法:
    python3 tools/encrypt_scripts.py                 # 加密（就地改写）
    python3 tools/encrypt_scripts.py --decrypt       # 还原（调试用）

算法必须与 ScriptHost.decode() 严格一致：
    MAGIC(4B) + (明文 XOR key[i % 32])
    key 由 SEED + "com.github.catvod.spider.ScriptHost" 派生

注意：
  - 脚本运行时仍会还原成明文写到 filesDir，这只能防静态扒取；
  - 加密脚本需重新 build.sh（资源是编译期打进 jar 的）；
  - 未加密的脚本 ScriptHost 也能正常读（靠 MAGIC 头自动识别）。
"""

import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))

MAGIC = b'WHS1'
SEED = 'webhome-script-v1'
CLASS_NAME = 'com.github.catvod.spider.ScriptHost'


def seed_key():
    """与 ScriptHost.seedKey() 逐字节对齐"""
    seed = (SEED + CLASS_NAME).encode('utf-8')
    key = bytearray(32)
    acc = 0x5A
    for i in range(32):
        v = seed[i % len(seed)]
        acc = (acc * 31 + v) & 0xFF
        key[i] = v ^ acc
    return bytes(key)


def transform(data: bytes, key: bytes) -> bytes:
    out = bytearray(len(data))
    for i in range(len(data)):
        out[i] = data[i] ^ key[i % len(key)]
    return bytes(out)


def encrypt(path, key):
    raw = open(path, 'rb').read()
    if raw[:4] == MAGIC:
        print('  [SKIP] 已加密: %s' % os.path.basename(path))
        return False
    open(path, 'wb').write(MAGIC + transform(raw, key))
    return True


def decrypt(path, key):
    raw = open(path, 'rb').read()
    if raw[:4] != MAGIC:
        print('  [SKIP] 未加密: %s' % os.path.basename(path))
        return False
    open(path, 'wb').write(transform(raw[4:], key))
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dir', default=os.path.join(ROOT, 'sdk', 'build', 'res', 'scripts'))
    ap.add_argument('--decrypt', action='store_true')
    args = ap.parse_args()

    d = os.path.abspath(args.dir)
    if not os.path.isdir(d):
        print('目录不存在（先跑一次 build.sh 生成 res）: %s' % d)
        return 1

    key = seed_key()
    files = sorted(f for f in os.listdir(d) if f.endswith(('.js', '.py')))
    if not files:
        print('没有可处理的脚本')
        return 0

    action = decrypt if args.decrypt else encrypt
    verb = '还原' if args.decrypt else '加密'
    n = 0
    for f in files:
        if action(os.path.join(d, f), key):
            n += 1
            print('  [%s] %s' % (verb, f))
    print('%s %d 个脚本 -> %s' % (verb, n, d))
    if not args.decrypt:
        print('\n记得重新 bash build.sh，让加密后的脚本打进 jar。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
