// 江苏名师空中课堂 —— ext 参数完美支持版（type:3）
// 配置示例:
//   "ext": {"我的课程": ["初中|初三|数学|苏科版|上册", "初中|初三|语文|人教版|上册"]}

const HOST = "https://mskzkt.jse.edu.cn";
const BASE_API = "https://mskzkt.jse.edu.cn/baseApi";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

const STAGES = [
    { id: "1", name: "小学", class_id: "primary" },
    { id: "2", name: "初中", class_id: "junior" },
    { id: "3", name: "高中", class_id: "senior" },
];

// 版本别名：配置里常见的写法 -> 网站实际返回的名称
const VERSION_ALIASES = {
    "苏教版": "苏科版",
};

// 全局状态
let MY_COURSES = [];
let cache = {
    grade: {}, subject: {}, version: {}, volumn: {},
    dir: {}, resource: {}, tencent: {}, playUrl: {},
};

// ======================== 网络请求 ========================
async function _post(path, data) {
    try {
        const res = await req(BASE_API + path, {
            method: "post",
            headers: {
                "User-Agent": UA, "Referer": HOST + "/",
                "Content-Type": "application/x-www-form-urlencoded",
                "Accept": "application/json, text/javascript, */*; q=0.01",
                "Origin": HOST,
            },
            data: data || {}, postType: "form", timeout: 15000
        });
        // TVBox 不同版本返回字段可能为 content 或 body
        const body = res.content || res.body || "";
        return body ? JSON.parse(body) : { state: -1 };
    } catch (e) { return { state: -1, message: String(e) }; }
}

// ======================== 辅助方法 ========================
function _fixUrl(url) {
    if (!url) return "";
    if (url.indexOf("//") === 0) return "https:" + url;
    if (url.indexOf("/") === 0) return HOST + url;
    return url;
}
function _clean(t) { return t ? String(t).replace(/<[^>]+>/g, "").replace(/\s+/g, " ").trim() : ""; }
function _ph() { return { "User-Agent": UA, "Referer": HOST + "/", "Origin": HOST }; }
function _courseKey(c) { return c.stage_id + "_" + c.grade_id + "_" + c.subject_id + "_" + c.version_id + "_" + c.volumn_id; }
function _chapterCore(title) {
    return String(title || "").replace(/^第[一二三四五六七八九十\d]+[章单元]\s*/, "").replace(/[的之]/g, "").replace(/[—\-–]/g, "").trim();
}
function _safeId(name) {
    return name.replace(/[^a-zA-Z0-9\u4e00-\u9fa5]/g, "_").replace(/_+/g, "_").replace(/^_+|_+$/g, "").toLowerCase();
}
function _has(str, sub) { return str.indexOf(sub) !== -1; }

// ======================== 数据获取 ========================
async function _fetchGradeList(stageId) {
    if (!cache.grade[stageId]) {
        const d = await _post("/seyk/grade/list/", { stage_id: stageId });
        cache.grade[stageId] = (d.state === 0 ? d.data.grade_list : []) || [];
    }
    return cache.grade[stageId];
}
async function _fetchSubjectList(stageId, gradeId) {
    const k = stageId + "_" + gradeId;
    if (!cache.subject[k]) {
        const d = await _post("/seyk/subject/list/", { stage_id: stageId, grade_id: gradeId });
        cache.subject[k] = (d.state === 0 ? d.data.subject_list : []) || [];
    }
    return cache.subject[k];
}
async function _fetchVersionList(subjectId, gradeId) {
    const k = subjectId + "_" + gradeId;
    if (!cache.version[k]) {
        const d = await _post("/seyk/version/list/", { subject_id: subjectId, grade_id: gradeId });
        cache.version[k] = (d.state === 0 ? d.data.version_list : []) || [];
    }
    return cache.version[k];
}
async function _fetchVolumnList(subjectId, gradeId, versionId) {
    const k = subjectId + "_" + gradeId + "_" + versionId;
    if (!cache.volumn[k]) {
        const d = await _post("/seyk/volumn/list/", { subject_id: subjectId, grade_id: gradeId, version_id: versionId });
        cache.volumn[k] = (d.state === 0 ? d.data.volumn_list : []) || [];
    }
    return cache.volumn[k];
}
async function _fetchDirectoryList(course) {
    const k = _courseKey(course);
    if (!cache.dir[k]) {
        const d = await _post("/seyk/directory/list/", {
            subject_id: course.subject_id, version_id: course.version_id,
            volumn_id: course.volumn_id, grade_id: course.grade_id,
        });
        cache.dir[k] = (d.state === 0 ? d.data.directory_list : []) || [];
    }
    return cache.dir[k];
}
async function _fetchResourceList(course, dirId, dirLevel, page, limit) {
    const d = await _post("/seyk/resource/list/", {
        subject_id: course.subject_id, version_id: course.version_id,
        volumn_id: course.volumn_id, dir_id: dirId || "",
        page: String(page), limit: String(limit),
        grade_id: course.grade_id, dir_level: dirLevel || "",
    });
    return (d.state === 0 ? d.data.resource_list : []) || [];
}
async function _fetchResourceDetail(resourceId) {
    if (!cache.resource[resourceId]) {
        const d = await _post("/seyk/resource/detail/", { resource_id: String(resourceId) });
        cache.resource[resourceId] = d.state === 0 ? d.data.resource_info : null;
    }
    return cache.resource[resourceId];
}

// ======================== 课程名解析（核心）========================
async function _resolveCourseName(name) {
    // 支持 "·" 或 "|" 分隔
    const parts = name.split(/[·|]/).map(function(s) { return s.trim(); }).filter(function(s) { return s; });
    if (parts.length < 3) {
        console.log("[课程解析] 格式错误，至少需要学段|年级|学科: " + name);
        return null;
    }

    // 1. 提取学段
    let stageId = "";
    let stageName = "";
    for (let i = 0; i < STAGES.length; i++) {
        const st = STAGES[i];
        if (_has(name, st.name)) { stageId = st.id; stageName = st.name; break; }
    }
    if (!stageId) {
        console.log("[课程解析] 未识别学段: " + name);
        return null;
    }

    // 2. 提取年级
    const grades = await _fetchGradeList(stageId);
    let grade = null;
    let gradeName = "";
    for (let i = 0; i < grades.length; i++) {
        const g = grades[i];
        if (_has(name, g.grade_title)) { grade = g; gradeName = g.grade_title; break; }
    }
    if (!grade) {
        console.log("[课程解析] 未识别年级: " + name + ", 可用年级: " + grades.map(function(g) { return g.grade_title; }).join(","));
        return null;
    }

    // 3. 过滤已识别的宽泛字段，避免 "初中" 命中 "初中语文"
    const filteredParts = parts.filter(function(p) { return p !== stageName && p !== gradeName; });

    // 4. 提取学科
    const subjects = await _fetchSubjectList(stageId, grade.grade_id);
    let subject = null;
    for (let i = 0; i < subjects.length; i++) {
        const s = subjects[i];
        for (let j = 0; j < filteredParts.length; j++) {
            const p = filteredParts[j];
            if (_has(p, s.subject_title) || _has(s.subject_title, p)) {
                subject = s; break;
            }
        }
        if (subject) break;
    }
    if (!subject) {
        console.log("[课程解析] 未识别学科: " + name + ", 候选: " + subjects.map(function(s) { return s.subject_title; }).join(","));
        return null;
    }

    // 5. 提取版本（支持别名映射）
    const versions = await _fetchVersionList(subject.subject_id, grade.grade_id);
    let version = null;
    for (let i = 0; i < versions.length; i++) {
        const v = versions[i];
        for (let j = 0; j < filteredParts.length; j++) {
            const rawP = filteredParts[j];
            const p = VERSION_ALIASES[rawP] || rawP;
            if (_has(p, v.version_title) || _has(v.version_title, p)) {
                version = v; break;
            }
        }
        if (version) break;
    }
    if (!version) {
        console.log("[课程解析] 未识别版本: " + name + ", 候选: " + versions.map(function(v) { return v.version_title; }).join(","));
        return null;
    }

    // 6. 提取册次
    const volumns = await _fetchVolumnList(subject.subject_id, grade.grade_id, version.version_id);
    let volumn = null;
    for (let i = 0; i < volumns.length; i++) {
        const vol = volumns[i];
        for (let j = 0; j < filteredParts.length; j++) {
            const p = filteredParts[j];
            if (_has(p, vol.volumn_title) || _has(vol.volumn_title, p)) {
                volumn = vol; break;
            }
        }
        if (volumn) break;
    }
    if (!volumn) {
        console.log("[课程解析] 未识别册次: " + name + ", 候选: " + volumns.map(function(v) { return v.volumn_title; }).join(","));
        return null;
    }

    // 构造显示名称: 初三数学|苏科版上册
    const subjectShort = subject.subject_title.replace(stageName, "");
    const displayName = grade.grade_title + subjectShort + "|" + version.version_title + volumn.volumn_title;
    const result = {
        type_id: _safeId(displayName),
        type_name: displayName,
        stage_id: stageId,
        grade_id: grade.grade_id,
        subject_id: subject.subject_id,
        version_id: version.version_id,
        volumn_id: volumn.volumn_id,
    };
    console.log("[课程解析] 成功: " + name + " -> 显示名=" + displayName + ", stage=" + stageId + ", grade=" + grade.grade_id + ", subject=" + subject.subject_id + ", version=" + version.version_id + ", volumn=" + volumn.volumn_id);
    return result;
}

async function _resolveCourseNames(names) {
    const results = [];
    for (let i = 0; i < names.length; i++) {
        const c = await _resolveCourseName(names[i]);
        if (c) results.push(c);
    }
    return results;
}

// ======================== 章节去重合并 ========================
async function _mergeChapters(course, chapters) {
    const groups = {};
    for (let i = 0; i < chapters.length; i++) {
        const ch = chapters[i];
        const core = _chapterCore(ch.dir_title);
        if (!groups[core]) groups[core] = [];
        groups[core].push(ch);
    }
    const merged = [];
    for (const core in groups) {
        const group = groups[core];
        let title = group[0].dir_title;
        for (let i = 0; i < group.length; i++) {
            const ch = group[i];
            if ((ch.child_dir || []).length > 0) { title = ch.dir_title; break; }
        }
        const epStrs = [];
        const seenIds = new Set();
        for (let i = 0; i < group.length; i++) {
            const ch = group[i];
            const childDirs = ch.child_dir || [];
            if (childDirs.length === 0) {
                const resList = await _fetchResourceList(course, ch.dir_id, "2", 1, 100);
                for (let j = 0; j < resList.length; j++) {
                    const r = resList[j];
                    if (seenIds.has(r.resource_id)) continue;
                    seenIds.add(r.resource_id);
                    const name = (r.resource_title || "视频").replace(/\$/g, "＄").replace(/#/g, "＃");
                    epStrs.push(name + "$seyk_" + r.resource_id);
                }
            } else {
                for (let j = 0; j < childDirs.length; j++) {
                    const sec = childDirs[j];
                    const secName = (sec.dir_title || "").replace(/\$/g, "＄").replace(/#/g, "＃");
                    const resList = await _fetchResourceList(course, sec.dir_id, "3", 1, 100);
                    if (resList.length === 0) continue;
                    if (resList.length === 1) {
                        const r = resList[0];
                        if (seenIds.has(r.resource_id)) continue;
                        seenIds.add(r.resource_id);
                        const name = secName || (r.resource_title || "视频").replace(/\$/g, "＄").replace(/#/g, "＃");
                        epStrs.push(name + "$seyk_" + r.resource_id);
                    } else {
                        const ids = [];
                        for (let k = 0; k < resList.length; k++) {
                            const r = resList[k];
                            if (seenIds.has(r.resource_id)) continue;
                            seenIds.add(r.resource_id);
                            ids.push(r.resource_id);
                        }
                        if (ids.length > 0) epStrs.push(secName + "$multi_" + ids.join("|"));
                    }
                }
            }
        }
        if (epStrs.length === 0) continue;
        merged.push({ title: title.replace(/\$/g, "＄").replace(/#/g, "＃"), episodes: epStrs.join("#") });
    }
    return merged;
}

// ======================== 获取课程下所有视频（平铺，支持章节筛选）========================
async function _fetchCourseVideos(course, chapterFilter, page, limit) {
    const dirs = await _fetchDirectoryList(course);
    const targetDir = dirs.find(function(d) { return String(d.dir_id) === String(course.volumn_id); }) || dirs[0];
    if (!targetDir) return { items: [], total: 0 };

    const chapters = targetDir.child_dir || [];
    const allItems = [];
    const seenIds = new Set();

    for (let i = 0; i < chapters.length; i++) {
        const ch = chapters[i];
        const chTitle = ch.dir_title || "";
        if (chapterFilter && _has(_chapterCore(chTitle), chapterFilter) === false && _has(chTitle, chapterFilter) === false) continue;

        const childDirs = ch.child_dir || [];
        if (childDirs.length === 0) {
            const resList = await _fetchResourceList(course, ch.dir_id, "2", 1, 100);
            for (let j = 0; j < resList.length; j++) {
                const r = resList[j];
                if (seenIds.has(r.resource_id)) continue;
                seenIds.add(r.resource_id);
                allItems.push({
                    vod_id: "seyk_" + r.resource_id,
                    vod_name: r.resource_title || "",
                    vod_pic: _fixUrl(r.seal_img || ""),
                    vod_remarks: r.view || "",
                    vod_year: chTitle,
                });
            }
        } else {
            for (let j = 0; j < childDirs.length; j++) {
                const sec = childDirs[j];
                const secTitle = sec.dir_title || "";
                const resList = await _fetchResourceList(course, sec.dir_id, "3", 1, 100);
                for (let k = 0; k < resList.length; k++) {
                    const r = resList[k];
                    if (seenIds.has(r.resource_id)) continue;
                    seenIds.add(r.resource_id);
                    const name = secTitle ? secTitle + "·" + (r.resource_title || "") : (r.resource_title || "");
                    allItems.push({
                        vod_id: "seyk_" + r.resource_id,
                        vod_name: name,
                        vod_pic: _fixUrl(r.seal_img || ""),
                        vod_remarks: r.view || "",
                        vod_year: chTitle,
                    });
                }
            }
        }
    }

    const total = allItems.length;
    const start = (page - 1) * limit;
    return { items: allItems.slice(start, start + limit), total };
}

// ======================== 获取课程章节列表（用于筛选器）========================
async function _fetchChapterOptions(course) {
    const dirs = await _fetchDirectoryList(course);
    const targetDir = dirs.find(function(d) { return String(d.dir_id) === String(course.volumn_id); }) || dirs[0];
    if (!targetDir) return [{ n: "全部", v: "" }];

    const opts = [{ n: "全部", v: "" }];
    const seen = new Set();
    const childDirs = targetDir.child_dir || [];
    for (let i = 0; i < childDirs.length; i++) {
        const ch = childDirs[i];
        const title = ch.dir_title || "";
        const core = _chapterCore(title);
        if (seen.has(core)) continue;
        seen.add(core);
        opts.push({ n: title, v: core });
    }
    return opts;
}

// ======================== 腾讯云播放解析 ========================
async function _fetchTencentMultiQuality(appId, fileId, psign) {
    const k = appId + "_" + fileId;
    if (cache.tencent[k]) return cache.tencent[k];
    try {
        const url = "https://playvideo.qcloud.com/getplayinfo/v4/" + appId + "/" + fileId + "?psign=" + psign;
        const res = await req(url, { method: "get", headers: _ph(), timeout: 15000 });
        if (!res.content && !res.body) return [];
        const d = JSON.parse(res.content || res.body);
        if (d.code !== 0) return [];
        const media = d.media || {};
        const streaming = media.streamingInfo || {};
        const result = [];
        const qmap = { "240": "流畅", "480": "标清", "720": "高清", "1080": "全高清" };

        const adaptive = streaming.adaptiveDynamicStreamingInfo || {};
        if (adaptive.url) result.push("自适应", adaptive.url);
        const plain = streaming.plainOutput || {};
        if (plain.url) result.push("原画", plain.url);
        const videoInfo = media.videoInfo || {};
        const transcodeList = videoInfo.transcodeList || [];
        for (let i = 0; i < transcodeList.length; i++) {
            const t = transcodeList[i];
            if (t.url) result.push(qmap[String(t.height)] || t.height + "P", t.url);
        }
        const source = videoInfo.sourceVideo || {};
        if (source.url && result.length === 0) result.push("原画", source.url);

        cache.tencent[k] = result;
        return result;
    } catch (e) { return []; }
}

async function _getTencentPlayUrl(appId, fileId, psign) {
    const multi = await _fetchTencentMultiQuality(appId, fileId, psign);
    if (multi.length >= 2) return multi;
    try {
        const url = "https://playvideo.qcloud.com/getplayinfo/v4/" + appId + "/" + fileId + "?psign=" + psign;
        const res = await req(url, { method: "get", headers: _ph(), timeout: 15000 });
        if (!res.content && !res.body) return "";
        const d = JSON.parse(res.content || res.body);
        if (d.code !== 0) return "";
        const media = d.media || {};
        const streaming = media.streamingInfo || {};
        const adaptive = streaming.adaptiveDynamicStreamingInfo || {};
        if (adaptive.url) return adaptive.url;
        const plain = streaming.plainOutput || {};
        if (plain.url) return plain.url;
        const videoInfo = media.videoInfo || {};
        const source = videoInfo.sourceVideo || {};
        if (source.url) return source.url;
        const transcodeList = videoInfo.transcodeList || [];
        if (transcodeList.length > 0 && transcodeList[0].url) return transcodeList[0].url;
    } catch (e) { }
    return "";
}

// ======================== 标准接口 ========================
async function init(cfg) {
    cache = { grade: {}, subject: {}, version: {}, volumn: {}, dir: {}, resource: {}, tencent: {}, playUrl: {} };
    MY_COURSES = [];

    let ext = {};
    // TVBox 不同版本传递方式不同：
    // 1. cfg 直接是 ext 的 JSON 字符串
    // 2. cfg 是对象，且 cfg.ext 是字符串或对象
    // 3. cfg 直接就是 ext 对象
    if (typeof cfg === "string") {
        try { ext = JSON.parse(cfg); } catch (e) { ext = {}; }
    } else if (typeof cfg === "object" && cfg !== null) {
        if (cfg.ext !== undefined) {
            if (typeof cfg.ext === "string") {
                try { ext = JSON.parse(cfg.ext); } catch (e) { ext = {}; }
            } else {
                ext = cfg.ext;
            }
        } else {
            ext = cfg;
        }
    }

    console.log("[init] ext 类型: " + typeof ext + ", 键: " + Object.keys(ext).join(","));

    // 从 "我的课程" 数组解析（唯一数据源，不再硬编码兜底）
    const courseNames = ext["我的课程"] || ext["courses"] || [];
    console.log("[init] 课程名数量: " + courseNames.length);
    if (Array.isArray(courseNames) && courseNames.length > 0) {
        MY_COURSES = await _resolveCourseNames(courseNames);
    }

    if (MY_COURSES.length === 0) {
        console.log("[init] ext 中未配置有效课程");
    } else {
        console.log("[init] 成功加载 " + MY_COURSES.length + " 门课程");
    }

    return JSON.stringify({ code: 0, msg: "success" });
}

async function home(filter) {
    try {
        const classList = [];
        for (let i = 0; i < MY_COURSES.length; i++) {
            const c = MY_COURSES[i];
            classList.push({ type_id: c.type_id, type_name: c.type_name });
        }
        for (let i = 0; i < STAGES.length; i++) {
            const st = STAGES[i];
            classList.push({ type_id: st.class_id, type_name: st.name });
        }

        const filters = {};
        for (let i = 0; i < MY_COURSES.length; i++) {
            const c = MY_COURSES[i];
            const chapterOpts = await _fetchChapterOptions(c);
            filters[c.type_id] = [{ key: "chapter", name: "章节", value: chapterOpts }];
        }
        for (let i = 0; i < STAGES.length; i++) {
            const st = STAGES[i];
            const gradeOpts = [{ n: "全部", v: "" }];
            const subjectOpts = [{ n: "全部", v: "" }];
            const versionOpts = [{ n: "全部", v: "" }];
            const volumnOpts = [{ n: "全部", v: "" }];
            const seenSub = new Set(), seenVer = new Set(), seenVol = new Set();

            const grades = await _fetchGradeList(st.id);
            for (let j = 0; j < grades.length; j++) gradeOpts.push({ n: grades[j].grade_title, v: grades[j].grade_id });

            for (let j = 0; j < grades.length; j++) {
                const g = grades[j];
                const subs = await _fetchSubjectList(st.id, g.grade_id);
                for (let k = 0; k < subs.length; k++) {
                    const s = subs[k];
                    if (!seenSub.has(s.subject_id)) {
                        seenSub.add(s.subject_id);
                        subjectOpts.push({ n: s.subject_title, v: s.subject_id });
                    }
                    const vers = await _fetchVersionList(s.subject_id, g.grade_id);
                    for (let m = 0; m < vers.length; m++) {
                        const v = vers[m];
                        if (!seenVer.has(v.version_id)) {
                            seenVer.add(v.version_id);
                            versionOpts.push({ n: v.version_title, v: v.version_id });
                        }
                        const vols = await _fetchVolumnList(s.subject_id, g.grade_id, v.version_id);
                        for (let n = 0; n < vols.length; n++) {
                            const vol = vols[n];
                            if (!seenVol.has(vol.volumn_id)) {
                                seenVol.add(vol.volumn_id);
                                volumnOpts.push({ n: vol.volumn_title, v: vol.volumn_id });
                            }
                        }
                    }
                }
            }
            filters[st.class_id] = [
                { key: "grade", name: "年级", value: gradeOpts },
                { key: "subject", name: "学科", value: subjectOpts },
                { key: "version", name: "版本", value: versionOpts },
                { key: "volumn", name: "册次", value: volumnOpts },
            ];
        }

        return JSON.stringify({ class: classList, filters: filters });
    } catch (e) {
        return JSON.stringify({ class: [], filters: {} });
    }
}

async function homeVod() {
    return JSON.stringify({ list: [] });
}

async function category(tid, pg, filter, extend) {
    const page = parseInt(pg || "1", 10);
    const tidStr = String(tid);

    try {
        let ext = {};
        if (typeof extend === "string" && extend.trim()) { try { ext = JSON.parse(extend); } catch (e) { ext = {}; } }
        else if (typeof extend === "object" && extend !== null) ext = extend;

        const course = MY_COURSES.find(function(c) { return c.type_id === tidStr; });
        if (course) {
            const chapterFilter = ext.chapter || "";
            const { items, total } = await _fetchCourseVideos(course, chapterFilter, page, 20);
            const pagecount = Math.ceil(total / 20) || 1;
            return JSON.stringify({ page: page, pagecount: pagecount, limit: 20, total: total, list: items });
        }

        const stage = STAGES.find(function(s) { return s.class_id === tidStr; });
        if (stage) {
            let gradeId = ext.grade || "";
            let subjectId = ext.subject || "";
            let versionId = ext.version || "";
            let volumnId = ext.volumn || "";

            const grades = await _fetchGradeList(stage.id);
            if (!gradeId && grades.length > 0) gradeId = grades[0].grade_id;
            if (gradeId) {
                const subjects = await _fetchSubjectList(stage.id, gradeId);
                if (!subjectId && subjects.length > 0) subjectId = subjects[0].subject_id;
                if (subjectId) {
                    const versions = await _fetchVersionList(subjectId, gradeId);
                    if (!versionId && versions.length > 0) versionId = versions[0].version_id;
                    if (versionId) {
                        const volumns = await _fetchVolumnList(subjectId, gradeId, versionId);
                        if (!volumnId && volumns.length > 0) volumnId = volumns[0].volumn_id;
                    }
                }
            }

            if (!gradeId || !subjectId || !versionId || !volumnId) {
                return JSON.stringify({ page: 1, pagecount: 1, limit: 20, total: 0, list: [] });
            }

            const c = { stage_id: stage.id, grade_id: gradeId, subject_id: subjectId, version_id: versionId, volumn_id: volumnId };
            const { items, total } = await _fetchCourseVideos(c, "", page, 20);
            const pagecount = Math.ceil(total / 20) || 1;
            return JSON.stringify({ page: page, pagecount: pagecount, limit: 20, total: total, list: items });
        }

        return JSON.stringify({ page: 1, pagecount: 1, limit: 20, total: 0, list: [] });
    } catch (e) {
        return JSON.stringify({ page: 1, pagecount: 1, limit: 20, total: 0, list: [] });
    }
}

async function detail(id) {
    const vid = String(id);

    if (vid.indexOf("course_") === 0) {
        const typeId = vid.replace("course_", "");
        const course = MY_COURSES.find(function(c) { return c.type_id === typeId; });
        if (!course) return JSON.stringify({ list: [] });

        const dirs = await _fetchDirectoryList(course);
        const targetDir = dirs.find(function(d) { return String(d.dir_id) === String(course.volumn_id); }) || dirs[0];
        if (!targetDir) return JSON.stringify({ list: [] });

        const merged = await _mergeChapters(course, targetDir.child_dir || []);
        if (merged.length === 0) return JSON.stringify({ list: [] });

        const playFromList = [];
        const playUrlList = [];
        for (let i = 0; i < merged.length; i++) {
            playFromList.push(merged[i].title);
            playUrlList.push(merged[i].episodes);
        }

        return JSON.stringify({
            list: [{
                vod_id: vid,
                vod_name: course.type_name,
                vod_pic: "",
                vod_content: "",
                vod_remarks: "",
                vod_play_from: playFromList.join("$$$"),
                vod_play_url: playUrlList.join("$$$"),
            }]
        });
    }

    const parts = vid.split("_");
    if (parts.length < 2) return JSON.stringify({ list: [] });
    const resourceId = parts[1];

    const info = await _fetchResourceDetail(resourceId);
    if (!info) return JSON.stringify({ list: [] });

    const title = info.title || info.resource_title || "";
    const pic = _fixUrl(info.seal_img || info.thumb || "");
    const desc = info.description || "";
    const view = info.view || "";

    return JSON.stringify({
        list: [{
            vod_id: vid, vod_name: title, vod_pic: pic,
            vod_content: _clean(desc), vod_remarks: view,
            vod_play_from: "默认线路", vod_play_url: "播放$" + vid,
        }]
    });
}

async function play(flag, id, flags) {
    const vid = String(id || "");

    if (vid.indexOf("multi_") === 0) {
        const ids = vid.replace("multi_", "").split("|");
        const result = [];
        for (let i = 0; i < ids.length; i++) {
            const rid = ids[i];
            const info = await _fetchResourceDetail(rid);
            if (!info || !info.file_id) continue;
            const vodData = await _post("/base/vod/", { file_id: info.file_id });
            if (vodData.state !== 0) continue;
            const appId = vodData.data.app_id || "";
            const psign = vodData.data.psign || "";
            if (!appId || !psign) continue;
            const multi = await _fetchTencentMultiQuality(appId, info.file_id, psign);
            if (multi.length >= 2) {
                result.push("课时" + (i + 1), multi[1]);
            } else {
                const url = await _getTencentPlayUrl(appId, info.file_id, psign);
                if (url) result.push("课时" + (i + 1), url);
            }
        }
        if (result.length >= 2) {
            return JSON.stringify({ parse: 0, url: result, header: _ph() });
        }
        return JSON.stringify({ parse: 0, url: "", msg: "多视频解析失败" });
    }

    const parts = vid.split("_");
    if (parts.length < 2) return JSON.stringify({ parse: 0, url: "", msg: "无效ID" });
    const resourceId = parts[1];

    if (cache.playUrl[vid]) {
        return JSON.stringify({ parse: 0, url: cache.playUrl[vid], header: _ph() });
    }

    const info = await _fetchResourceDetail(resourceId);
    if (!info) return JSON.stringify({ parse: 0, url: "", msg: "无资源" });

    const fileId = info.file_id || "";
    if (!fileId) return JSON.stringify({ parse: 0, url: "", msg: "无file_id" });

    const vodData = await _post("/base/vod/", { file_id: fileId });
    if (vodData.state !== 0) return JSON.stringify({ parse: 0, url: "", msg: "签名失败" });

    const appId = vodData.data.app_id || "";
    const psign = vodData.data.psign || "";
    if (!appId || !psign) return JSON.stringify({ parse: 0, url: "", msg: "无签名" });

    const multi = await _fetchTencentMultiQuality(appId, fileId, psign);
    if (multi.length >= 2) {
        cache.playUrl[vid] = multi;
        return JSON.stringify({ parse: 0, url: multi, header: _ph() });
    }

    const url = await _getTencentPlayUrl(appId, fileId, psign);
    if (!url) return JSON.stringify({ parse: 0, url: "", msg: "播放地址失败" });
    cache.playUrl[vid] = url;
    return JSON.stringify({ parse: 0, url: url, header: _ph() });
}

async function search(key, quick, pg) {
    const page = parseInt(pg || "1", 10);
    let decoded = key;
    try { decoded = decodeURIComponent(String(key)); } catch (e) { decoded = String(key); }

    const allItems = [];
    for (let i = 0; i < MY_COURSES.length; i++) {
        const c = MY_COURSES[i];
        const { items } = await _fetchCourseVideos(c, "", 1, 9999);
        for (let j = 0; j < items.length; j++) {
            const item = items[j];
            if (item.vod_name && _has(item.vod_name, decoded)) {
                allItems.push(item);
            }
        }
    }

    const perPage = 20;
    const total = allItems.length;
    const pagecount = Math.ceil(total / perPage) || 1;
    const start = (page - 1) * perPage;
    return JSON.stringify({
        list: allItems.slice(start, start + perPage),
        page: page, pagecount: pagecount, limit: perPage, total: total,
    });
}

// ======================== 导出 ========================
export default {
    init: init,
    home: home,
    homeVod: homeVod,
    category: category,
    detail: detail,
    play: play,
    search: search,
};
