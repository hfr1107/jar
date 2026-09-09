/*
 * demo.js — 内置脚本示例（可被 csp_Script / csp_WebHome 调用）
 *
 * 调用方式（站点配置）：
 *   {"key":"csp_Script","name":"JS脚本示例","type":3,"api":"csp_Script","ext":"js:demo"}
 *   {"key":"csp_WebHome","name":"JS脚本首页","type":3,"api":"csp_WebHome","ext":"js:demo"}
 *
 * 运行时可用能力（同步返回，不需要 await）：
 *   fm.req(url, options)            -> "{ok,status,body,url}"  JSON 字符串
 *   fm.res(url, options)            -> 图片资源 URL（走直连/代理分流）
 *   fm.log(msg)                     -> 打到 logcat，tag = WebHomeScript
 *   fm.script("js:other", fn, args) -> 调用另一个内置脚本
 *
 * 约定：
 *   1. 直接 return 一个 JSON 字符串即可，原样回传给壳；
 *   2. 也可以 return 对象，引擎会帮你 JSON.stringify；
 *   3. 下面这些函数名是 Spider 生命周期的入口，按需实现，不用全写；
 *   4. 想暴露自定义入口，用 __exports = { ... }，
 *      之后 ext 里用 js:demo?func=myFunc 或 action("script:js:demo?func=myFunc") 调用。
 */

function homeContent(filter) {
  return JSON.stringify({
    "class": [{ "type_id": "1", "type_name": "示例分类" }],
    "list": [{
      "vod_id": "1",
      "vod_name": "来自 demo.js 的条目",
      "vod_pic": fm.res("https://example.com/poster.jpg"),
      "vod_remarks": "JS"
    }],
    "__from": "demo.js"
  });
}

function categoryContent(tid, pg, filter, extend) {
  return JSON.stringify({ "page": pg, "pagecount": 1, "list": [], "tid": tid });
}

function detailContent(ids) {
  var list = [];
  for (var i = 0; i < ids.length; i++) {
    list.push({
      "vod_id": ids[i],
      "vod_name": "详情 " + ids[i],
      "vod_play_from": "demo",
      "vod_play_url": "第1集$https://example.com/a.m3u8"
    });
  }
  return JSON.stringify({ "list": list });
}

function searchContent(key, quick, pg) {
  // 同步 HTTP：返回体是 {ok,status,body,url} 的 JSON 字符串
  // var res = JSON.parse(fm.req("https://example.com/s?wd=" + encodeURIComponent(key)));
  return JSON.stringify({ "list": [], "key": key, "pg": pg });
}

function playerContent(flag, id, vipFlags) {
  return JSON.stringify({
    "parse": 0,
    "playUrl": "",
    "url": id,
    "header": {},
    "message": "from demo.js"
  });
}

function action(name, arg) {
  if (name === "ping") return JSON.stringify({ "pong": true });
  return JSON.stringify({ "action": name, "arg": arg });
}

/* 自定义入口：ext 填 js:demo?func=hello 即可调用 */
var __exports = {
  hello: function (name) {
    fm.log("hello from demo.js: " + name);
    return JSON.stringify({ "hello": name || "world" });
  }
};
