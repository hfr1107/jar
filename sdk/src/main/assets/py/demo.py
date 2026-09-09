# -*- coding: utf-8 -*-
"""
demo.py — 内置脚本示例（可被 csp_Script / csp_WebHome 调用）

调用方式（站点配置）：
  {"key":"csp_Script","name":"PY脚本示例","type":3,"api":"csp_Script","ext":"py:demo"}
  {"key":"csp_WebHome","name":"PY脚本首页","type":3,"api":"csp_WebHome","ext":"py:demo"}

运行环境：
  Android 没有内置 Python，脚本由宿主壳的 Python 环境执行（目前主流壳用
  Chaquopy，即 com.chaquo.python.Python）。检测不到时 PyRunner 会返回
  {"error": ...}，你也可以用 PyRunner.setEngine(...) 自己注入引擎。

约定：
  1. 函数返回 JSON 字符串（推荐），引擎原样回传给壳；
  2. 返回 dict/list 时引擎会自动 json.dumps；
  3. 想暴露自定义入口，用 __exports = {"name": func} 字典，
     之后 ext 里用 py:demo?func=my_func 调用。
"""

import json


def homeContent(filter):
    return json.dumps({
        "class": [{"type_id": "1", "type_name": "示例分类"}],
        "list": [{
            "vod_id": "1",
            "vod_name": "来自 demo.py 的条目",
            "vod_pic": "https://example.com/poster.jpg",
            "vod_remarks": "PY",
        }],
        "__from": "demo.py",
    })


def categoryContent(tid, pg, filter, extend):
    return json.dumps({"page": pg, "pagecount": 1, "list": [], "tid": tid})


def detailContent(ids):
    return json.dumps({
        "list": [{
            "vod_id": i,
            "vod_name": "详情 %s" % i,
            "vod_play_from": "demo",
            "vod_play_url": "第1集$https://example.com/a.m3u8",
        } for i in ids]
    })


def searchContent(key, quick, pg):
    return json.dumps({"list": [], "key": key, "pg": pg})


def playerContent(flag, id, vipFlags):
    return json.dumps({"parse": 0, "playUrl": "", "url": id, "header": {}})


def action(name, arg):
    if name == "ping":
        return json.dumps({"pong": True})
    return json.dumps({"action": name, "arg": arg})


# 自定义入口：ext 填 py:demo?func=hello 即可调用
def hello(name=None):
    return json.dumps({"hello": name or "world"})


__exports = {"hello": hello}
