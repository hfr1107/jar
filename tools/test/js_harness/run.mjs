// 模拟影视TV JsLoader（QuickJS）：注入 req/console，加载 T4 脚本并调用生命周期
import fs from 'fs';
import path from 'path';

const file = process.argv[2];
const extArg = process.argv[3] || '';

// QuickJS 注入的全局能力（沙盒无网络，返回失败让脚本走降级分支）
globalThis.req = async function (url, opt) {
    return { code: -1, content: '', body: '', status: 0 };
};
globalThis.pdfh = () => '';
globalThis.pd = () => '';
globalThis.pdfa = () => [];
globalThis.joinUrl = (a, b) => b;
globalThis.MD5 = (s) => s;
globalThis.BASE = '';

const src = fs.readFileSync(file, 'utf8');
const tmp = path.join('/tmp', 'probe_' + Date.now() + '.mjs');
fs.writeFileSync(tmp, src, 'utf8');

console.log('=== 加载 %s ===', path.basename(file));
let mod;
try {
    mod = await import('file://' + tmp);
    console.log('  [OK] 模块加载成功 (ESM / export default)');
} catch (e) {
    console.log('  [FAIL] 加载失败: %s', e.message);
    process.exit(1);
}

const api = mod.default || mod;
const keys = Object.keys(api);
console.log('  [OK] 导出方法: %s', keys.join(', '));

for (const m of ['init', 'home', 'category', 'detail', 'play', 'search']) {
    if (!(m in api)) console.log('  [WARN] 缺少 %s', m);
}

if (typeof api.init === 'function') {
    try {
        const r = await api.init(extArg);
        console.log('  [OK] init(ext) -> %s', String(r).slice(0, 80));
    } catch (e) {
        console.log('  [FAIL] init 抛错: %s', e.message);
    }
}

if (typeof api.home === 'function') {
    try {
        const r = await api.home(false);
        const o = JSON.parse(r);
        const cls = (o.class || []).map(c => c.type_name);
        console.log('  [OK] home() -> %d 个分类: %s', cls.length, cls.join(' / '));
    } catch (e) {
        console.log('  [FAIL] home 抛错: %s', e.message);
    }
}

if (typeof api.search === 'function') {
    try {
        const r = await api.search('测试', false, '1');
        const o = JSON.parse(r);
        console.log('  [OK] search() -> list=%d total=%d', (o.list || []).length, o.total ?? -1);
    } catch (e) {
        console.log('  [INFO] search: %s', e.message);
    }
}

fs.unlinkSync(tmp);
