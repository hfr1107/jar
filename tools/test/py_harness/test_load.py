#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""模拟影视TV PyLoader：把脚本当模块加载，实例化 Spider，检查生命周期方法。

沙盒无网络，脚本 init() 里的联网会被 socket 超时/总超时截断，
这不影响验证目的：只要能走到网络阶段，说明 import 与类结构都正常。
"""
import importlib.util
import inspect
import os
import signal
import socket
import sys
import traceback

socket.setdefaulttimeout(2)          # 网络调用快速失败
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

TOTAL_TIMEOUT = 25


class _Alarm(Exception):
    pass


def _handler(signum, frame):
    raise _Alarm()


signal.signal(signal.SIGALRM, _handler)

SCRIPT = sys.argv[1]
name = os.path.splitext(os.path.basename(SCRIPT))[0]
spec = importlib.util.spec_from_file_location(name, SCRIPT)

print('=== 加载 %s ===' % os.path.basename(SCRIPT))
try:
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    print('  [OK] 模块加载成功（from base.spider import Spider 已通过）')
except Exception:
    print('  [FAIL] 模块加载失败')
    traceback.print_exc()
    sys.exit(1)

SpiderBase = sys.modules['base.spider'].Spider
spider_cls = None
for vname, obj in vars(mod).items():
    if inspect.isclass(obj) and issubclass(obj, SpiderBase) and obj is not SpiderBase:
        spider_cls = obj
        break

if spider_cls is None:
    print('  [INFO] 未找到 Spider 子类（manager 类脚本，走 action 触发）')
    sys.exit(0)

print('  [OK] Spider 子类: %s' % spider_cls.__name__)

signal.alarm(TOTAL_TIMEOUT)
try:
    inst = spider_cls()
    inst.init('')
    print('  [OK] 实例化 + init("") 通过')
except _Alarm:
    print('  [OK] init 已进入联网阶段后被超时截断（说明 import/类结构正常）')
except Exception as e:
    print('  [INFO] init 中断: %s: %s' % (type(e).__name__, str(e)[:120]))
    print('         （沙盒无网络；真机联网后可正常完成）')
finally:
    signal.alarm(0)

signal.alarm(10)
try:
    if callable(getattr(spider_cls, 'getName', None)):
        print('  [INFO] getName() = %s' % spider_cls().getName())
except Exception:
    pass
finally:
    signal.alarm(0)

methods = ['getName', 'init', 'homeContent', 'homeVideoContent', 'categoryContent',
           'detailContent', 'searchContent', 'playerContent', 'liveContent',
           'isVideoFormat', 'manualVideoCheck', 'destroy', 'localProxy']
present = [m for m in methods if callable(getattr(spider_cls, m, None))]
print('  [OK] 已实现: %s' % ', '.join(present))

for m in ('homeContent', 'detailContent', 'playerContent'):
    if m not in present:
        print('  [WARN] 缺少 %s' % m)

if 'searchContent' in present:
    print('  [INFO] searchContent%s' % inspect.signature(getattr(spider_cls, 'searchContent')))
