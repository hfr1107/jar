#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把完整 classes.dex 加密成 payload.bin。

用法:
    python3 tools/pack_payload.py <in.dex> <out.bin>       # 加密
    python3 tools/pack_payload.py --decrypt <in.bin> <out.dex>   # 还原（调试）

算法必须与 SecureSpider.decode() 严格一致：
    MAGIC(4B, 'WHP1') + (明文 XOR key[i % 32])
    key 由 SEED + "com.github.catvod.spider.SecureSpider" 派生

build.sh 在 SECURE=1 时自动调用，一般不用手动跑。
"""

import argparse
import os
import sys

MAGIC = b'WHP1'
SEED = 'webhome-payload-v1'
CLASS_NAME = 'com.github.catvod.spider.SecureSpider'


def seed_key():
    """与 SecureSpider.seedKey() 逐字节对齐"""
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--decrypt', action='store_true')
    ap.add_argument('src')
    ap.add_argument('dst')
    args = ap.parse_args()

    if not os.path.isfile(args.src):
        print('文件不存在: %s' % args.src)
        return 1

    key = seed_key()
    raw = open(args.src, 'rb').read()

    if args.decrypt:
        if raw[:4] != MAGIC:
            print('不是加密 payload（无 MAGIC 头）')
            return 1
        out = transform(raw[4:], key)
    else:
        out = MAGIC + transform(raw, key)

    d = os.path.dirname(os.path.abspath(args.dst))
    if d and not os.path.isdir(d):
        os.makedirs(d)
    open(args.dst, 'wb').write(out)
    verb = '解密' if args.decrypt else '加密'
    print('%s: %s (%d B) -> %s (%d B)' % (verb, args.src, len(raw), args.dst, len(out)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
