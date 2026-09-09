package com.github.catvod.spider;

/**
 * FmSdk — 把 webhtv HomeWebController.getSdk() 的 JS 字符串原样打包。
 * 不要从 assets 读，直接作为 Java 字符串常量内嵌。
 *
 * 这里用占位符 %s 留给 caller 注入 (mode, isLeanback, debugHook)。
 */
public final class FmSdk {

    private FmSdk() {}

    /**
     * 返回完整 SDK JS 字符串。mode / isLeanback / debugHook 由调用方填入。
     */
    public static String get(String mode, boolean isLeanback, String debugHook) {
        return String.format(java.util.Locale.ROOT, TEMPLATE, mode, isLeanback ? "true" : "false", debugHook);
    }

    public static String get(String mode, boolean isLeanback) {
        return get(mode, isLeanback, "");
    }

    private static final String TEMPLATE =
        "(function(){\n" +
        "  if(window.fm&&window.fongmi){window.dispatchEvent(new CustomEvent('fmsdk'));return;}\n" +
        "  if(document&&document.documentElement)document.documentElement.classList.add('fm-native');\n" +
        "  window.fongmiClient={mode:'%s',isLeanback:%s};\n" +
        "  const callbacks={};\n" +
        "  let seq=0;\n" +
        "  function invoke(method,payload){\n" +
        "    return new Promise((resolve,reject)=>{\n" +
        "      const id='fm_'+Date.now()+'_'+(++seq);\n" +
        "      callbacks[id]={resolve,reject};\n" +
        "      fongmiBridge.invoke(id,method,JSON.stringify(payload||{}));\n" +
        "    });\n" +
        "  }\n" +
        "  function hydrate(data){\n" +
        "    if(!data||!data.__fmResultId)return data;\n" +
        "    const resultId=data.__fmResultId;\n" +
        "    const length=fongmiBridge.resultLength(resultId);\n" +
        "    let text='';\n" +
        "    for(let start=0;start<length;start+=60000)text+=fongmiBridge.resultChunk(resultId,start);\n" +
        "    fongmiBridge.clearResult(resultId);\n" +
        "    return JSON.parse(text);\n" +
        "  }\n" +
        "  window.fongmiNative={\n" +
        "    resolve:(id,data)=>{ if(callbacks[id]){ callbacks[id].resolve(hydrate(data)); delete callbacks[id]; } },\n" +
        "    reject:(id,error)=>{ if(callbacks[id]){ callbacks[id].reject(new Error(error||'')); delete callbacks[id]; } }\n" +
        "  };\n" +
        "  if(!window.__fmUrlHook&&window.history){\n" +
        "    window.__fmUrlHook=true;\n" +
        "    const emit=()=>window.dispatchEvent(new CustomEvent('fmurlchange',{detail:{url:location.href}}));\n" +
        "    const rawPush=history.pushState;\n" +
        "    const rawReplace=history.replaceState;\n" +
        "    history.pushState=function(){const r=rawPush.apply(this,arguments);emit();return r;};\n" +
        "    history.replaceState=function(){const r=rawReplace.apply(this,arguments);emit();return r;};\n" +
        "    window.addEventListener('popstate',emit);\n" +
        "  }\n" +
        "  %s\n" +
        "  const player={\n" +
        "    playUrl:(url,title,options)=>invoke('player.playUrl',Object.assign({},options||{},{url,title})),\n" +
        "    playVod:(siteKey,vodId,title,pic,options)=>invoke('player.playVod',Object.assign({},options||{},{siteKey,vodId,title,pic})),\n" +
        "    playVodInline:(payload)=>invoke('player.playVodInline',payload||{}),\n" +
        "    preloadArtwork:(pic,wallPic)=>invoke('player.preloadArtwork',{pic,wallPic}),\n" +
        "    control:(action)=>invoke('player.control',{action}),\n" +
        "    status:()=>invoke('player.status',{})\n" +
        "  };\n" +
        "  const net={\n" +
        "    request:(url,options)=>invoke('net.request',Object.assign({},options||{},{url})),\n" +
        "    resourceUrl:(url,options)=>fongmiBridge.resourceUrl(url,JSON.stringify(options||{}))\n" +
        "  };\n" +
        "  const cache={\n" +
        "    get:(key,rule)=>invoke('cache.get',{key,rule}),\n" +
        "    set:(key,value,rule)=>invoke('cache.set',{key,value,rule}),\n" +
        "    del:(key,rule)=>invoke('cache.del',{key,rule})\n" +
        "  };\n" +
        "  const pan={\n" +
        "    check:(items)=>invoke('pan.check',{items}),\n" +
        "    play:(payload)=>invoke('pan.play',payload||{})\n" +
        "  };\n" +
        "  const ext={\n" +
        "    info:()=>invoke('ext.info',{}),\n" +
        "    log:(message,data)=>invoke('ext.log',{message,data}),\n" +
        "    toast:(message)=>invoke('ext.toast',{message})\n" +
        "  };\n" +
        "  const ui={\n" +
        "    setToolbar:(visible)=>invoke('ui.setToolbar',{visible:visible!==false}),\n" +
        "    setChrome:(options)=>invoke('ui.setChrome',options||{}),\n" +
        "    restoreChrome:()=>invoke('ui.restoreChrome',{}),\n" +
        "    getViewport:()=>invoke('ui.getViewport',{})\n" +
        "  };\n" +
        "  window.fongmi={invoke,player,net,cache,\n" +
        "    app:{\n" +
        "      search:(keyword,options)=>invoke('app.search',Object.assign({},options||{},{keyword})),\n" +
        "      openVod:()=>invoke('app.openVod',{}),\n" +
        "      openLive:()=>invoke('app.openLive',{}),\n" +
        "      openKeep:()=>invoke('app.openKeep',{}),\n" +
        "      openSetting:()=>invoke('app.openSetting',{}),\n" +
        "      history:()=>invoke('app.history',{})\n" +
        "    },\n" +
        "    pan,\n" +
        "    ext,\n" +
        "    device:{info:()=>invoke('device.info',{})},\n" +
        "    site:{info:()=>invoke('site.info',{})},\n" +
        "    config:{info:()=>invoke('config.info',{})},\n" +
        "    ui,\n" +
        "    navigation:{\n" +
        "      back:()=>invoke('navigation.back',{}),\n" +
        "      reload:()=>invoke('navigation.reload',{})\n" +
        "    }\n" +
        "  };\n" +
        "  window.fm={\n" +
        "    req:net.request,\n" +
        "    res:net.resourceUrl,\n" +
        "    play:player.playUrl,\n" +
        "    vod:player.playVod,\n" +
        "    vodInline:player.playVodInline,\n" +
        "    preloadArtwork:player.preloadArtwork,\n" +
        "    ctrl:player.control,\n" +
        "    stat:player.status,\n" +
        "    search:window.fongmi.app.search,\n" +
        "    openVod:window.fongmi.app.openVod,\n" +
        "    openLive:window.fongmi.app.openLive,\n" +
        "    openKeep:window.fongmi.app.openKeep,\n" +
        "    openSetting:window.fongmi.app.openSetting,\n" +
        "    history:window.fongmi.app.history,\n" +
        "    pan,\n" +
        "    check:window.fongmi.pan.check,\n" +
    "    script:(spec, func, args)=>fongmiBridge.runScript(spec, func||'', JSON.stringify(args||[])),\n" +
        "    cache,\n" +
        "    ext,\n" +
        "    ui,\n" +
        "    device:window.fongmi.device.info,\n" +
        "    site:window.fongmi.site.info,\n" +
        "    config:window.fongmi.config.info,\n" +
        "    back:window.fongmi.navigation.back,\n" +
        "    reload:window.fongmi.navigation.reload\n" +
        "  };\n" +
        "  window.dispatchEvent(new CustomEvent('fmsdk'));\n" +
        "})();\n";
}
