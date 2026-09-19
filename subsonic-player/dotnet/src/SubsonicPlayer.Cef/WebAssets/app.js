'use strict';

// ============ 全局加载指示 ============
// 所有页面数据都经 Bridge.data 这一个入口，所以在这里统一挂加载指示：
// 服务器在 NAS / 外网时代码要等一下，没有任何提示会让人以为界面卡死。
// 延迟 120ms 才显示，避免局域网快请求时闪一下。
const Loading = (() => {
    let inflight = 0;
    let showTimer = null;
    let el = null;

    function ensure() {
        if (el && el.isConnected) return el;
        el = document.getElementById('globalLoading');
        if (!el) {
            el = document.createElement('div');
            el.id = 'globalLoading';
            el.className = 'global-loading';
            el.innerHTML = '<div class="gl-bar"></div><div class="gl-text">正在加载…</div>';
            document.body.appendChild(el);
        }
        return el;
    }

    return {
        start() {
            inflight++;
            if (inflight === 1) {
                clearTimeout(showTimer);
                showTimer = setTimeout(() => { ensure().classList.add('visible'); }, 120);
            }
        },
        end() {
            inflight = Math.max(0, inflight - 1);
            if (inflight === 0) {
                clearTimeout(showTimer);
                if (el) el.classList.remove('visible');
            }
        },
        /** 页面级提示：进入尚未加载的页面时立刻显示（不等 120ms） */
        showNow() {
            ensure().classList.add('visible');
        },
        hide() {
            if (el) el.classList.remove('visible');
        },
    };
})();

// ============ Bridge 封装 ============
// window.bridge 由 C# 注入（RegisterJavascriptObject），提供对 C# 服务的调用。

// 数据请求兜底超时。只用来兜住「请求永远不返回」这种把界面永久卡死的情况，不是性能阈值：
// C# 侧单请求最坏链路 = 15s（HttpClient 超时）+ 探测回退 + 15s（重连后重试一次）≈ 42s，
// 所以取 60s。超时后 finally 里的 Loading.end() 一定执行 —— 否则服务器一挂，
// "正在加载…" 遮罩和重连横幅会一直挂在界面上，看起来就是「窗口卡住了」。
const DATA_TIMEOUT_MS = 60000;

const Bridge = {
    invoke(method, ...args) {
        if (!window.bridge) return Promise.reject(new Error('bridge 未就绪'));
        const fn = window.bridge[method];
        if (typeof fn !== 'function') return Promise.reject(new Error(`方法不存在: ${method}`));
        return fn.apply(window.bridge, args);
    },
    // 页面数据：走统一入口 bridge.invokeData(method, argsJson)，由 C# 在 UI 线程调度
    async data(method, ...args) {
        if (!window.bridge) return Promise.reject(new Error('bridge 未就绪'));
        const fn = window.bridge.invokeData;
        if (typeof fn !== 'function') return Promise.reject(new Error('invokeData 未就绪'));
        const argsJson = JSON.stringify(args.map(a => a === undefined ? null : a));
        Loading.start();
        let timer = null;
        try {
            return await Promise.race([
                fn(method, argsJson),
                new Promise((_, reject) => {
                    timer = setTimeout(
                        () => reject(new Error(`服务器响应超时（${method}）`)),
                        DATA_TIMEOUT_MS);
                }),
            ]);
        } finally {
            if (timer !== null) clearTimeout(timer);
            Loading.end();
        }
    },
    // 后台填充用：**不挂全局"正在加载"遮罩**。
    //
    // 有些请求天生就慢，而且只是「补区块」不是「首屏内容」：
    //   · getDiscoverMore —— 要拉几十个接口（艺术家专辑列表 + 专辑详情 + 随机歌曲），
    //     限并发后仍要几秒；它只负责发现页下半部分的专辑区块（占位骨架已经在那儿了）。
    //   · getPagerTotals —— 要算全库专辑/歌曲总量，实测 6~9s；只影响分页器能点几页。
    //   · getArtistCover / getArtistPhoto —— 每行一位艺术家，纯装饰。
    // 这些走 Bridge.data 会把整个界面顶着一个"正在加载…"几十秒，看起来就是"一直正在加载"。
    async dataQuiet(method, ...args) {
        if (!window.bridge) return Promise.reject(new Error('bridge 未就绪'));
        const fn = window.bridge.invokeData;
        if (typeof fn !== 'function') return Promise.reject(new Error('invokeData 未就绪'));
        const argsJson = JSON.stringify(args.map(a => a === undefined ? null : a));
        return await fn(method, argsJson);
    },
};

// ============ 双击去重 ============
// 鼠标双击会触发两次 click（就连桌面播放器的双击播放也会），若两次 click 命中同一播放动作，
// 会重复执行导致歌曲连续加载两次。playOnce 保证同一 key 的播放在 400ms 窗口内只触发一次
// （取系统双击判定时间上限，可靠拦截双击，同时不影响快速重播不同歌曲）。
let _lastPlay = { key: '', at: 0 };
function playOnce(key, fn) {
    const now = Date.now();
    if (key === _lastPlay.key && now - _lastPlay.at < 400) return;
    _lastPlay = { key, at: now };
    fn();
}

// ============ 状态推送（C# → JS） ============
const StateBridge = {
    listeners: {},
    on(event, fn) { (this.listeners[event] = this.listeners[event] || []).push(fn); },
    emit(event, payload) { (this.listeners[event] || []).forEach(fn => fn(payload)); },
};

window.addEventListener('bridgeEvent', e => {
    const { event, payload } = e.detail || {};
    if (event) StateBridge.emit(event, payload);
});

// 窗口被隐藏 / 最小化到托盘时停掉背景流光：CEF 是离屏渲染，页面一直在动就一直重绘，
// 看不见的时候没有理由继续烧 CPU。CSS 侧见 styles.css「流光开关」。
document.addEventListener('visibilitychange', () => {
    document.documentElement.classList.toggle('window-hidden', document.hidden);
});

// ============ 导航 ============
// group：分组标题（数组里按顺序插入小标题，顺序与原来保持一致，不改变使用习惯）
const NAV_ITEMS = [
    { key: 'discover', label: '发现', icon: '#i-compass' },
    { key: 'nowPlaying', label: '正在播放', icon: '#i-now' },
    { key: 'albums', label: '专辑', icon: '#i-album', group: '曲库' },
    { key: 'artists', label: '艺术家', icon: '#i-artist' },
    { key: 'songs', label: '歌曲', icon: '#i-music' },
    { key: 'playlists', label: '歌单', icon: '#i-list' },
    { key: 'genres', label: '风格', icon: '#i-genre' },
    { key: 'favorites', label: '收藏', icon: '#i-heart-nav', group: '我的' },
    { key: 'history', label: '历史', icon: '#i-history' },
    { key: 'bookmarks', label: '书签', icon: '#i-bookmark' },
    { key: 'search', label: '搜索', icon: '#i-search', group: '其它' },
];

let currentPage = 'discover';

const PAGE_IDS = {
    discover: 'pageDiscover', albums: 'pageAlbums', albumDetail: 'pageAlbumDetail',
    search: 'pageSearch', playlists: 'pagePlaylists', playlistDetail: 'pagePlaylistDetail',
    genres: 'pageGenres', genreDetail: 'pageGenreDetail',
    songs: 'pageSongs', artists: 'pageArtists', artistDetail: 'pageArtistDetail',
    favorites: 'pageFavorites', history: 'pageHistory', bookmarks: 'pageBookmarks',
    nowPlaying: 'pageNowPlaying', placeholder: 'pagePlaceholder',
};

/// 沉浸页：整屏都是一张封面（正在播放 + 各详情页），底图几乎不压 —— 参考手机播放器的效果。
/// 列表页要靠玻璃厚度压住小字，所以这两类页面在 CSS 里是两套底色（见 body.immersive）。
const IMMERSIVE_PAGES = new Set(['nowPlaying', 'albumDetail', 'artistDetail', 'playlistDetail', 'genreDetail']);

function showPage(key) {
    for (const k of Object.keys(PAGE_IDS)) {
        const el = document.getElementById(PAGE_IDS[k]);
        if (el) el.style.display = k === key ? '' : 'none';
    }
    currentPage = key;
    document.body.classList.toggle('immersive', IMMERSIVE_PAGES.has(key));
}

function initNav() {
    const list = document.getElementById('navList');
    NAV_ITEMS.forEach(item => {
        if (item.group) {
            const h = document.createElement('div');
            h.className = 'nav-group';
            h.textContent = item.group;
            list.appendChild(h);
        }
        const btn = document.createElement('button');
        btn.className = 'nav-item';
        btn.dataset.key = item.key;
        btn.innerHTML = `<span class="nav-icon"><svg width="17" height="17"><use href="${item.icon}"/></svg></span><span>${item.label}</span>`;
        btn.addEventListener('click', () => navigate(item.key));
        list.appendChild(btn);
    });
}

function navigate(key) {
    // 离开详情页：恢复全局封面底图为正在播放的封面
    clearDetailBg();
    document.querySelectorAll('.nav-item').forEach(b => {
        b.classList.toggle('active', b.dataset.key === key);
    });
    const pageKey = (key === 'albumDetail' || key === 'playlistDetail' || key === 'artistDetail' || key === 'genreDetail') ? 'placeholder' : key;
    showPageWithTransition(pageKey);
    switch (key) {
        case 'discover': loadDiscover(); break;
        case 'albums': loadAlbums(1); break;
        case 'playlists': loadPlaylists(); break;
        case 'genres': loadGenres(); break;
        case 'songs': loadSongs(1); break;
        case 'artists': loadArtists(1); break;
        case 'favorites': loadFavorites(); break;
        case 'history': loadHistory(); break;
        case 'bookmarks': loadBookmarks(); break;
        case 'nowPlaying':
            // 每次进入播放页都播放入场动画（重置 songId 标记）
            _lastNowPlayingSongId = '';
            renderNowPlaying();
            break;
        case 'search':
            // 聚焦搜索页输入框（页面显示后）
            setTimeout(() => {
                const pi = document.getElementById('pageSearchInput');
                if (pi) pi.focus();
            }, 80);
            break;
        default: showPageWithTransition('placeholder');
    }
}

// 页面切换淡入过渡
function showPageWithTransition(key) {
    const current = document.querySelector('.page[style*="display: block"], .page:not([style*="display: none"])');
    showPage(key);
    const el = document.getElementById(PAGE_IDS[key] || 'pagePlaceholder');
    if (el) {
        el.style.animation = 'none';
        void el.offsetWidth; // reflow 重置动画
        el.style.animation = 'pageFadeIn 0.18s ease';
    }
}

// 页面数据缓存：已加载的页面切回时直接复用，不重复请求
const pageCache = {};
function cached(key, loader) {
    return async function (...args) {
        const ck = key + ':' + JSON.stringify(args);
        if (pageCache[ck]) return pageCache[ck];
        const data = await loader(...args);
        pageCache[ck] = data;
        return data;
    };
}

// ============ 通用渲染 ============

/// 统一分页器：`首页 | 上一页 | 1 2 3 … | 下一页`。
///
/// 总页数由 C# 给：艺术家索引是完整取回的；专辑数 = 艺术家索引 albumCount 求和；
/// 歌曲数 = 逐块走专辑累加 songCount；流派歌曲数 = getGenres 里那个流派的 songCount。
/// 都给不出时才退回"目前已经知道存在的最远一页"（靠 hasMore + 翻页推进）。
///
/// `…` 出现在数字窗口没贴住两端的时候：**左边那个往回翻一格，右边那个直接跳到末页**
/// —— 几百页的列表（专辑 176 页、歌曲 626 页）只靠"下一页"是走不到后面的。
/// `下一页` 在已知没有更多时变灰（不是藏起来，藏起来会让人以为坏了）。
function pagerHtml(kind, page, maxPage, hasMore) {
    const last = Math.max(1, maxPage || 1);
    const canNext = hasMore !== false && page < last + (hasMore === false ? 0 : 1);
    const btn = (label, target, opts = {}) => {
        const disabled = opts.disabled ? ' disabled' : '';
        const cls = 'pager-btn' + (opts.active ? ' active' : '') + (opts.ellipsis ? ' ellipsis' : '');
        const pageAttr = opts.disabled ? '' : ` data-page="${target}"`;
        const title = opts.title ? ` title="${esc(opts.title)}"` : '';
        return `<button class="${cls}"${pageAttr} data-pager="${esc(kind)}"${title}${disabled}>${label}</button>`;
    };

    // 数字窗口：以当前页为中心最多 5 个，且尽量贴住第 1 页（前几页时窗口右移而不是左空）。
    const windowSize = 5;
    let from = Math.max(1, page - Math.floor(windowSize / 2));
    let to = Math.min(last, from + windowSize - 1);
    from = Math.max(1, to - windowSize + 1);

    // ★ 曾经漏了「上一页」：只有 首页/数字/下一页，往回退只能点具体页码或左边那个 `…`，
    //   翻到后面页时想退一页很别扭。现在补齐，第 1 页时变灰。
    const parts = [
        btn('首页', 1, { disabled: page === 1, title: '回到第 1 页' }),
        btn('上一页', page - 1, { disabled: page <= 1, title: page > 1 ? `第 ${page - 1} 页` : '已经是第 1 页' }),
    ];
    if (from > 1) parts.push(btn('…', from - 1, { ellipsis: true, title: `回到第 ${from - 1} 页` }));
    for (let p = from; p <= to; p++) parts.push(btn(String(p), p, { active: p === page }));
    if (to < last) parts.push(btn('…', last, { ellipsis: true, title: `跳到最后一页（第 ${last} 页）` }));
    parts.push(btn('下一页', page + 1, { disabled: !canNext, title: `第 ${page + 1} 页` }));

    return `<div class="pager">${parts.join('')}</div>`;
}

/// 每个分页列表自己的状态：当前页、已知最远的一页、还有没有更多。
/// 放在一个表里，是为了让"分页器长什么样"和"谁来加载第 N 页"这两件事只有一份定义。
const PAGER = {
    albums: { page: 1, max: 1, more: true, load: p => loadAlbums(p) },
    songs: { page: 1, max: 1, more: true, load: p => loadSongs(p) },
    artists: { page: 1, max: 1, more: true, load: p => loadArtists(p) },
    artistSongs: { page: 1, max: 1, more: true, load: p => loadArtistSongs(p) },
    genreSongs: { page: 1, max: 1, more: true, load: p => loadGenreSongs(p) },
};

/// 记下一页的"地形"：这一页有多少内容、服务端说还有没有更多。
/// `count === 0` 是权威结论（这一页不存在），比 hasMore 的估计更可信。
function pagerObserve(kind, page, count, hasMore, totalPages) {
    const st = PAGER[kind];
    if (!st) return;
    st.page = page;
    if (totalPages) st.max = Math.max(1, totalPages);
    if (count > 0) st.max = Math.max(st.max, page);
    if (count === 0) { st.max = Math.max(1, page - 1); st.more = false; }
    else if (hasMore !== undefined) st.more = hasMore !== false;
    pagerMount(kind);
}

/// 把分页器画进它自己的容器（每个列表页一个）。
function pagerMount(kind) {
    const host = document.querySelector(`[data-pager-host="${kind}"]`);
    if (!host) return;
    const st = PAGER[kind];
    host.innerHTML = pagerHtml(kind, st.page, st.max, st.more);
}

/// 曲库规模 ⇒ 专辑/歌曲分页器的**确切总页数**。
/// 服务端的 getAlbumList2 / 歌曲列表本身不给总数（只有 hasMore），所以以前刚进页面只能画到
/// "首页 上一页 1 下一页"。C# 那边能算出来（专辑数 = 艺术家索引 albumCount 求和；歌曲数 = 逐块
/// 走专辑累加 songCount，缓存 30 分钟），这里与列表并行拉一次，回来就把页码补全。
/// 会话内只拉一次；换服务器要清标记（C# 侧缓存按服务 id 区分）。
let _pagerTotalsDone = false;
async function ensurePagerTotals() {
    if (_pagerTotalsDone) return;
    _pagerTotalsDone = true;
    try {
        const t = await Bridge.dataQuiet('getPagerTotals');
        let mounted = false;
        if (t?.albumPages > 0) { PAGER.albums.max = Math.max(PAGER.albums.max, t.albumPages); mounted = true; }
        if (t?.songPages > 0) { PAGER.songs.max = Math.max(PAGER.songs.max, t.songPages); mounted = true; }
        if (mounted) { pagerMount('albums'); pagerMount('songs'); }
    } catch (e) {
        _pagerTotalsDone = false;   // 失败就别记住，下次进页面再试
    }
}

/// 点分页器：跳到那一页（替换当前列表，而不是往下追加 —— 分页的语义是"翻页"）。
function pagerGo(kind, page) {
    const st = PAGER[kind];
    if (!st || !isFinite(page) || page < 1) return;
    if (page > st.max + 1) return;          // 别跳进还没证明存在的页码
    st.load(page);
}

function esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
    }[c]));
}

function fmtTime(sec) {
    if (!isFinite(sec) || sec < 0) return '0:00';
    sec = Math.floor(sec);
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    const s = sec % 60;
    return (h > 0 ? `${h}:${String(m).padStart(2, '0')}` : `${m}`) + `:${String(s).padStart(2, '0')}`;
}

/// 艺术家名字：知道 artistId 时渲染成可点击的链接，点了进该艺术家的单曲列表；
/// 不知道 id 的平台（Plex / Emby / AudioStation 用名字当 id，或者老的缓存数据）退回纯文本。
/// 这里刻意不做"点了没反应"的死链接——那比不能点更让人困惑。
function artistLinkHtml(name, artistId) {
    const label = esc(name || '');
    if (!label) return '';
    if (!artistId) return label;
    // data-artist-name 是兜底用的：歌曲里的 artistId 有可能不在艺术家索引里（只在 featuring 里出现的
    // 合作者），那时按名字再搜一次，胜过甩一句"艺术家不存在"。
    return `<span class="artist-link" data-artist-id="${esc(artistId)}" data-artist-name="${label}" title="查看该艺术家的单曲">${label}</span>`;
}

function albumCardHtml(a) {
    const cover = a.coverUrl || '';
    return `<div class="album-card" data-id="${esc(a.id)}" data-name="${esc(a.name)}">
        <div class="album-cover-wrap">
            ${cover ? `<img src="${cover}" alt="" onerror="this.style.display='none';this.nextElementSibling.style.display='flex'">` : ''}
            <div class="cover-fallback"><svg class="ic" width="34" height="34"><use href="#i-album"/></svg></div>
            <div class="play-overlay"><button class="play-btn" data-play-album="${esc(a.id)}"><svg class="ic" width="18" height="18"><use href="#i-now"/></svg></button></div>
        </div>
        <div class="album-name">${esc(a.name)}</div>
        <div class="album-artist">${artistLinkHtml(a.artist, a.artistId)}</div>
    </div>`;
}

/// 一行歌曲。`opts.linkArtist === false` 时不把歌手名做成链接 —— 歌手页里每一首都是这个歌手的，
/// 在那里点歌手的名字等于点"我正在看的这一页"，是多余的（用户提的正是这一条）。
///
/// ⚠️ opts 允许是 undefined，也允许是**数字**：`songs.map(songRowHtml)` 会把下标当第二个参数传进来。
/// 所以这里只认对象，其它一律当作"没给选项"。
function songRowHtml(s, opts) {
    const o = (opts && typeof opts === 'object') ? opts : {};
    const cover = s.coverUrl || '';
    const songJson = encodeURIComponent(JSON.stringify(s));
    const artist = o.linkArtist === false ? esc(s.artist || '') : artistLinkHtml(s.artist, s.artistId);
    return `<div class="song-row" data-id="${esc(s.id)}" data-idx="${s.index ?? ''}" data-song="${songJson}">
        <span class="song-index">${s.index ?? ''}</span>
        ${cover
            ? `<span class="song-cover-wrap"><img class="song-cover" src="${cover}" alt="" onerror="this.style.display='none';this.parentElement.classList.add('no-cover')"><svg class="cover-fb" width="18" height="18"><use href="#i-music"/></svg></span>`
            : `<span class="song-cover-wrap no-cover"><svg class="cover-fb" width="18" height="18"><use href="#i-music"/></svg></span>`}
        <span class="song-title">${esc(s.title)}</span>
        <span class="song-artist">${artist}</span>
        <span class="song-album">${esc(s.album || '')}</span>
        <span class="song-duration">${esc(s.durationText || '')}</span>
        <button class="song-fav ${s.isFavorite ? 'active' : ''}" data-fav="${esc(s.id)}">${s.isFavorite ? '♥' : '♡'}</button>
    </div>`;
}

/// 从 song-row 收集完整歌曲对象数组（用于播放）。
function collectSongs(listEl) {
    return [...listEl.querySelectorAll('.song-row')].map(r => {
        try { return JSON.parse(decodeURIComponent(r.dataset.song)); }
        catch (e) { return null; }
    }).filter(Boolean);
}

/// 标注当前播放的歌曲行（accent 强调 + 序号变播放图标）。
function markCurrentSong() {
    const s = latestPlayback;
    if (!s || !s.currentSongId) return;
    document.querySelectorAll('.song-row').forEach(row => {
        const isCurrent = row.dataset.id === s.currentSongId;
        row.classList.toggle('playing', isCurrent);
        // ★ 起播中转圈的行不能被这里覆盖：playback 推送每 ~500ms 来一次（进度变化），
        //   若在这里无条件重写 .song-index，markPlayPending 放的转圈会被秒清掉 ——
        //   表现就是"点了一下转圈闪一下就没了"。转圈的清除只归 clearPlayPending()。
        if (row.classList.contains('play-pending')) return;
        const idxEl = row.querySelector('.song-index');
        if (idxEl) idxEl.innerHTML = isCurrent
            ? '<svg width="14" height="14" class="ic eq-icon"><use href="#i-now"/></svg>'
            : row.dataset.idx || '';
    });
}

// ============ 轻量提示条（toast） ============
// 用于「播放失败」这类必须让用户知道、但又不该打断操作的信息。
let _toastTimer = null;
function showToast(text, ms = 5000) {
    let el = document.getElementById('appToast');
    if (!el) {
        el = document.createElement('div');
        el.id = 'appToast';
        el.className = 'app-toast';
        document.body.appendChild(el);
    }
    el.textContent = text;
    el.classList.add('visible');
    clearTimeout(_toastTimer);
    _toastTimer = setTimeout(() => el.classList.remove('visible'), ms);
}

// ============ 起播中提示 ============
// 点歌到出声之间，C# 要先 BASS 建流（连服务器 + 必要时降级下载整个文件），慢网下要几秒。
// 这段时间原来界面毫无反应，看起来像"点了不播"。这里点下立刻把该行序号换成转圈，
// 等 playback 事件确认这首歌成为当前曲目（或超时兜底）再恢复。
let _pendingPlayId = null;
let _pendingPlayTimer = null;

function markPlayPending(songId) {
    if (!songId) return;
    _pendingPlayId = songId;
    clearTimeout(_pendingPlayTimer);
    _pendingPlayTimer = setTimeout(clearPlayPending, 30000);   // 兜底：建流失败也不会一直转
    document.querySelectorAll('.song-row[data-id="' + CSS.escape(songId) + '"]').forEach(row => {
        row.classList.add('play-pending');
        const idxEl = row.querySelector('.song-index');
        if (idxEl) idxEl.innerHTML = '<span class="play-spinner"></span>';
    });
}

function clearPlayPending() {
    if (_pendingPlayId === null) return;
    _pendingPlayId = null;
    clearTimeout(_pendingPlayTimer);
    _pendingPlayTimer = null;
    const rows = document.querySelectorAll('.song-row.play-pending');
    rows.forEach(row => {
        row.classList.remove('play-pending');
        const idxEl = row.querySelector('.song-index');
        if (idxEl) idxEl.innerHTML = row.dataset.idx || '';
    });
}

// ============ 发现页（随机/智能 tab + 异步填充专辑） ============
let discoverTab = 'random';
let discoverTabLoaded = false;
let discoverMoreData = null; // getDiscoverMore 结果（智能推荐 + 专辑），本次会话复用

const DISCOVER_CACHE = 'discover_cache';

/// 今天日期 key（yyyy-MM-dd），用于「每天只刷新一次」
function todayKey() {
    const d = new Date();
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

/// 读取当天 tab 缓存（跨天自动失效）
function getTabCache(name) {
    try {
        const raw = localStorage.getItem(DISCOVER_CACHE + '_' + name);
        if (!raw) return null;
        const o = JSON.parse(raw);
        return o.date === todayKey() ? o.songs : null;
    } catch { return null; }
}
function setTabCache(name, songs) {
    try { localStorage.setItem(DISCOVER_CACHE + '_' + name, JSON.stringify({ date: todayKey(), songs })); } catch { }
}
function clearTabCache(name) {
    try { localStorage.removeItem(DISCOVER_CACHE + '_' + name); } catch { }
}

/// 歌曲列表骨架（固定点位，避免加载后跳动）
function skelRowsHtml(n) {
    let rows = '';
    for (let i = 0; i < n; i++) {
        rows += `<div class="skel-row">
            <div class="skel-block w3"></div>
            <div class="skel-block sq"></div>
            <div class="skel-block"></div>
            <div class="skel-block"></div>
        </div>`;
    }
    return `<div class="skel-section"><div class="skel-title"></div><div class="skel-rows">${rows}</div></div>`;
}
function skelAlbumsHtml() {
    return `<div class="section skel-section"><div class="skel-title"></div><div class="album-grid skel-albums">${'<div class="skel-card"></div>'.repeat(5)}</div></div>`;
}

/// 纯专辑/艺术家网格骨架（各列表页加载占位用）
function skelGridHtml(n) {
    return `<div class="skel-section"><div class="skel-rows"><div class="skel-row"><div class="skel-block sq"></div></div></div><div class="album-grid skel-albums">${'<div class="skel-card"></div>'.repeat(n || 6)}</div></div>`;
}

/// 歌曲列表骨架（各歌曲列表页加载占位用）
function skelSongListHtml(n) {
    return skelRowsHtml(n || 8);
}

/// 详情页骨架（封面 + 标题 + 歌曲行）
function skelDetailHtml() {
    return `<div class="skel-section">
        <div class="skel-row" style="margin-bottom:14px"><div class="skel-block sq" style="width:64px;height:64px;border-radius:8px"></div></div>
        <div class="skel-title"></div>
        <div class="skel-rows">${'<div class="skel-row"><div class="skel-block w3"></div><div class="skel-block sq"></div><div class="skel-block"></div><div class="skel-block"></div></div>'.repeat(6)}</div>
    </div>`;
}

async function loadDiscover() {
    const box = document.getElementById('pageDiscover');
    if (discoverTabLoaded) { markCurrentSong(); return; }
    // 全部区块先设好点位（骨架虚影占位），未加载到的都用占位，避免跳动
    document.getElementById('discoverTabBody').innerHTML = skelRowsHtml(5);
    document.getElementById('discoverMore').innerHTML = skelAlbumsHtml() + skelAlbumsHtml() + skelAlbumsHtml();
    // 从上到下顺序加载：随机/智能 tab → 最新专辑 → 常听专辑 → 高分专辑
    await loadDiscoverTab();
    await loadDiscoverMore();
    discoverTabLoaded = true;
}

/// 加载当前 tab（随机/智能）：当天缓存优先，否则请求并写缓存
async function loadDiscoverTab() {
    const body = document.getElementById('discoverTabBody');
    const isRandom = discoverTab === 'random';
    const cached = getTabCache(discoverTab);
    if (cached && cached.length) { renderTabBody(body, cached, isRandom); return; }
    // 智能 tab 可能已由专辑请求带回 recommendations
    if (!isRandom && discoverMoreData?.recommendations?.length) {
        const recs = discoverMoreData.recommendations;
        setTabCache('smart', recs);
        renderTabBody(body, recs, false);
        return;
    }
    body.innerHTML = skelRowsHtml(5);
    try {
        const d = isRandom
            ? await Bridge.data('getDiscoverQuick')
            // 智能 tab 自带骨架占位，同样走 dataQuiet（理由见 loadDiscoverMore）
            : await Bridge.dataQuiet('getDiscoverMore');
        const songs = isRandom ? (d.randomSongs || []) : (d.recommendations || []);
        if (!songs.length) {
            body.innerHTML = `<div class="status-line">${esc(d.status || '暂无内容')}</div>`;
            return;
        }
        setTabCache(discoverTab, songs);
        renderTabBody(body, songs, isRandom);
    } catch (err) {
        body.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
    }
}

/// 渲染 tab 内容（歌曲列表 + 播放全部）
function renderTabBody(body, songs, isRandom) {
    body.innerHTML = `<div class="section">
        <div class="section-header"><h2>${isRandom ? '随机推荐' : '智能推荐'}</h2>
        <button class="action" data-play-list="${isRandom ? 'random' : 'rec'}">播放全部</button></div>
        <div class="song-list">${songs.map(songRowHtml).join('')}</div>
    </div>`;
    markCurrentSong();
}

/// 异步加载专辑区块（最新/常听/高分），先占位后填充；同时带回智能推荐供 tab 复用。
/// 走 dataQuiet：这块只是"补区块"（骨架已占位），不该让全局"正在加载"遮罩跟着它一起等。
async function loadDiscoverMore() {
    const more = document.getElementById('discoverMore');
    try {
        const d = await Bridge.dataQuiet('getDiscoverMore');
        discoverMoreData = d;
        more.innerHTML = '';
        if (d.newestAlbums?.length)
            more.insertAdjacentHTML('beforeend', `<div class="section"><div class="section-header"><h2>最新专辑</h2></div><div class="album-grid">${d.newestAlbums.map(albumCardHtml).join('')}</div></div>`);
        if (d.frequentAlbums?.length)
            more.insertAdjacentHTML('beforeend', `<div class="section"><div class="section-header"><h2>常听专辑</h2></div><div class="album-grid">${d.frequentAlbums.map(albumCardHtml).join('')}</div></div>`);
        if (d.highestAlbums?.length)
            more.insertAdjacentHTML('beforeend', `<div class="section"><div class="section-header"><h2>高分专辑</h2></div><div class="album-grid">${d.highestAlbums.map(albumCardHtml).join('')}</div></div>`);
        // 若智能 tab 正显示骨架（无缓存且请求未到），用本次 recommendations 补上
        if (discoverTab === 'smart') {
            const body = document.getElementById('discoverTabBody');
            if (body.querySelector('.skel-section') && d.recommendations?.length) {
                setTabCache('smart', d.recommendations);
                renderTabBody(body, d.recommendations, false);
            }
        }
    } catch (e) { /* 专辑区块失败不影响已显示内容 */ }
}

/// 发现页 tab 切换 + 换一批
function initDiscoverEvents() {
    document.querySelectorAll('.dtab').forEach(btn => {
        btn.addEventListener('click', () => {
            if (btn.classList.contains('active')) return;
            document.querySelectorAll('.dtab').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            discoverTab = btn.dataset.tab;
            const body = document.getElementById('discoverTabBody');
            const cached = getTabCache(discoverTab);
            if (cached && cached.length) renderTabBody(body, cached, discoverTab === 'random');
            else { body.innerHTML = skelRowsHtml(5); loadDiscoverTab(); }
        });
    });
    document.getElementById('discoverRefresh').addEventListener('click', () => {
        // 换一批：清当天缓存强制刷新当前 tab
        clearTabCache(discoverTab);
        // 智能推荐的 recommendations 会复用 discoverMoreData（首次加载的结果）；
        // 换一批必须清掉它，否则 loadDiscoverTab 直接复用旧推荐、不重新请求 → "换一批无效"
        discoverMoreData = null;
        const body = document.getElementById('discoverTabBody');
        body.innerHTML = skelRowsHtml(5);
        loadDiscoverTab();
    });
}

// ============ 专辑列表（流式加载 + 首屏缓存） ============
let albumsPage = 1;
let albumsLoading = false;
const albumsCache = {}; // page -> { albums }

async function loadAlbums(page = 1) {
    albumsPage = page;
    showPageWithTransition('albums');
    const grid = document.getElementById('albumsGrid');
    const loading = document.getElementById('albumsLoading');
    loading.style.display = 'none';

    // 某一页取过就秒开，并把分页器画好（翻页是**替换**，不是追加）。
    if (albumsCache[page]) {
        grid.innerHTML = albumsCache[page].albums.map(albumCardHtml).join('')
            || '<div class="status-line">这一页是空的</div>';
        pagerObserve('albums', page, albumsCache[page].albums.length, albumsCache[page].hasMore);
        ensurePagerTotals();
        return;
    }

    grid.innerHTML = skelGridHtml();
    pagerObserve('albums', page, 1, true);
    ensurePagerTotals();
    await fetchAlbumsPage(page);
}

async function fetchAlbumsPage(page) {
    const grid = document.getElementById('albumsGrid');
    const loading = document.getElementById('albumsLoading');
    if (albumsLoading) return;

    albumsLoading = true;
    loading.style.display = '';
    try {
        const d = await Bridge.data('getAlbumsPage', page);
        loading.style.display = 'none';
        if (d.status) {
            grid.innerHTML = `<div class="status-line">${esc(d.status)}</div>`;
            pagerObserve('albums', page, 0, false);
            return;
        }

        const albums = d.albums || [];
        albumsCache[page] = { albums, hasMore: d.hasMore };
        grid.innerHTML = albums.map(albumCardHtml).join('') || '<div class="status-line">这一页是空的</div>';
        // C# 现在能直接给确切总页数（专辑数由艺术家索引算出）⇒ 页码一次画全；
        // 万一没给（老服务/算不出来），退回 hasMore + 逐页推进，行为不变。
        pagerObserve('albums', page, albums.length, d.hasMore, d.totalPages);
    } catch (err) {
        loading.style.display = 'none';
        grid.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
    } finally {
        albumsLoading = false;
    }
}

// 内容区滚动：不再自动加载下一页 —— 三个列表页改用分页器（首页 / 1 2 3 / 下一页）。
// 保留这个监听只为发现页的懒加载之类，不再有任何"滚到底部就追加"的行为：
// 追加和分页是两套心智模型，混在一起会出现"我点第 3 页，列表里却同时有 1、2、3 页的内容"。
function setupInfiniteScroll() {
    // 故意留空。删掉函数会牵动初始化顺序，而它的语义已经被分页器取代。
}

// ============ 专辑详情 ============
async function openAlbumDetail(id) {
    showPage('albumDetail');
    const body = document.getElementById('albumDetailBody');
    body.innerHTML = skelDetailHtml();
    try {
        const a = await Bridge.data('getAlbumDetail', id);
        if (!a) { body.innerHTML = '<div class="status-line">专辑不存在</div>'; return; }
        // 用专辑封面做模糊背景
        if (a.coverUrl) setDetailBg(a.coverUrl);
        body.innerHTML = `<div class="album-detail-header">
            ${a.coverUrl ? `<img class="album-detail-cover" src="${a.coverUrl}" alt="">` : `<div class="album-detail-cover"></div>`}
            <div class="album-detail-meta">
                <h1>${esc(a.name)}</h1>
                <div class="sub">${artistLinkHtml(a.artist, a.artistId)} · ${a.songCount || 0} 首 · ${esc(a.year || '')}</div>
                <button class="play-all-btn" data-play-album="${esc(a.id)}">播放全部</button>
            </div>
        </div>
        <div class="song-list">${(a.songs || []).map(songRowHtml).join('') || '<div class="status-line">暂无歌曲</div>'}</div>`;
    } catch (err) {
        body.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
    }
}

// ============ 歌单 ============
const loadPlaylistsData = cached('playlists', () => Bridge.data('getPlaylists'));

async function loadPlaylists() {
    showPageWithTransition('playlists');
    const grid = document.getElementById('playlistsGrid');
    if (grid.querySelector('.album-card')) return;
    grid.innerHTML = skelGridHtml();
    try {
        const d = await loadPlaylistsData();
        grid.innerHTML = (d.playlists || []).map(p => `
            <div class="album-card" data-playlist-id="${esc(p.id)}">
                <div class="album-cover-wrap">
                    ${p.coverUrl ? `<img src="${p.coverUrl}" alt="">` : ''}
                </div>
                <div class="album-name">${esc(p.name)}</div>
                <div class="album-artist">${p.songCount || 0} 首</div>
            </div>`).join('') || '<div class="status-line">暂无歌单</div>';
    } catch (err) {
        grid.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
    }
}

async function openPlaylistDetail(id) {
    showPage('playlistDetail');
    const body = document.getElementById('playlistDetailBody');
    body.innerHTML = skelDetailHtml();
    try {
        const p = await Bridge.data('getPlaylistDetail', id);
        if (!p) { body.innerHTML = '<div class="status-line">歌单不存在</div>'; return; }
        body.innerHTML = `<div class="album-detail-header">
            ${p.coverUrl ? `<img class="album-detail-cover" src="${p.coverUrl}" alt="">` : `<div class="album-detail-cover"></div>`}
            <div class="album-detail-meta">
                <h1>${esc(p.name)}</h1>
                <div class="sub">${esc(p.songCountText || '')}</div>
            </div>
        </div>
        <div class="song-list">${(p.songs || []).map(songRowHtml).join('') || '<div class="status-line">暂无歌曲</div>'}</div>`;
    } catch (err) {
        body.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
    }
}

// ============ 风格 / 流派 ============

/// 风格（流派）列表。服务器不支持 getGenres 时给友好提示（如 Gonic 无流派数据）。
async function loadGenres() {
    showPageWithTransition('genres');
    const grid = document.getElementById('genresGrid');
    if (grid.querySelector('.genre-card')) return;   // 已有内容不重复拉取
    grid.innerHTML = skelGridHtml(8);
    try {
        const d = await Bridge.data('getGenres');
        if (!d || !d.supported) {
            grid.innerHTML = `<div class="status-line">${esc(d?.status || '此服务器没有风格数据（服务端需支持 getGenres / getSongsByGenre）')}</div>`;
            return;
        }
        grid.innerHTML = `<div class="genre-grid">${(d.genres || []).map(g => `
            <div class="genre-card" data-genre="${esc(g.name)}">
                <div class="genre-name">${esc(g.name)}</div>
                <div class="genre-count">${esc(g.countText || '')}</div>
            </div>`).join('')}</div>`
            + (d.filtered > 0 ? `<div class="genre-note">已隐藏 ${d.filtered} 个「来源水印」流派（下载站写进标签的站名/论坛名等，非真实流派）</div>` : '');
        grid.querySelectorAll('.genre-card').forEach(c => {
            c.addEventListener('click', () => openGenreDetail(c.dataset.genre));
        });
    } catch (err) {
        grid.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
    }
}

/// 某个风格下的歌曲 —— 同样走统一分页器（每页 100 首），不再"一次拉 500 首"。
async function openGenreDetail(genre) {
    showPage('genreDetail');
    currentGenre = genre;
    const body = document.getElementById('genreDetailBody');
    body.innerHTML = skelDetailHtml();
    await loadGenreSongs(1, genre);
}

let currentGenre = '';

async function loadGenreSongs(page = 1, genre = currentGenre) {
    const body = document.getElementById('genreDetailBody');
    if (!body || !genre) return;
    if (page === 1) body.innerHTML = skelDetailHtml();
    pagerObserve('genreSongs', page, 1, true);
    try {
        const d = await Bridge.data('getSongsByGenre', genre, page);
        const songs = d?.songs || [];
        body.innerHTML = `<div class="album-detail-header">
            <div class="album-detail-cover genre-cover"><svg class="ic" width="42" height="42"><use href="#i-genre"/></svg></div>
            <div class="album-detail-meta">
                <h1>${esc(genre)}</h1>
                <div class="sub">${d?.total ? '共 ' + d.total + ' 首' : (songs.length ? '本页 ' + songs.length + ' 首' : '')}</div>
            </div>
        </div>
        <div class="song-list">${songs.map(s => songRowHtml(s)).join('') || '<div class="status-line">这一页是空的</div>'}</div>
        <div data-pager-host="genreSongs"></div>`;
        pagerObserve('genreSongs', page, songs.length, d?.hasMore, d?.totalPages);
        markCurrentSong();
    } catch (err) {
        body.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
    }
}

// ============ 歌曲（分页） ============
// 每页多少首由 C# 的 CefPageDataProvider.SongPageSize（=10）说了算 —— 前端只报页码，
// 不在两边各写一份数字，免得改一边忘一边。C# 会把「按专辑展开」的扁平列表按歌切片，
// 所以每页是**实打实的 10 首**，不是「20 张专辑展开出来多少算多少」。
let songsPage = 1;
let songsLoading = false;
const songsCache = {};

async function loadSongs(page = 1) {
    songsPage = page;
    songsLoading = false;
    showPage('songs');
    const list = document.getElementById('songsList');

    // 某一页取过就秒开；分页的语义是"翻页"，所以这里**替换**列表而不是往下追加。
    if (songsCache[page]) {
        list.innerHTML = songsCache[page].songs.map(s => songRowHtml(s)).join('') || '<div class="status-line">这一页是空的</div>';
        pagerObserve('songs', page, songsCache[page].songs.length, songsCache[page].hasMore);
        ensurePagerTotals();
        markCurrentSong();
        return;
    }

    list.innerHTML = skelSongListHtml();
    pagerObserve('songs', page, 1, true);
    ensurePagerTotals();
    await fetchSongsPage(page);
}

async function fetchSongsPage(page) {
    const list = document.getElementById('songsList');
    if (songsLoading) return;
    songsLoading = true;
    try {
        // 页大小交给 C# 的默认参数（SongPageSize=10），页内序号也由它算 —— 前端只报页码。
        const d = await Bridge.data('getSongsPage', page);
        if (d.status) {
            list.innerHTML = `<div class="status-line">${esc(d.status)}</div>`;
            pagerObserve('songs', page, 0, false);
            return;
        }

        const songs = d.songs || [];
        songsCache[page] = { songs, hasMore: d.hasMore };
        list.innerHTML = songs.map(s => songRowHtml(s)).join('')
            || '<div class="status-line">这一页是空的</div>';
        // 总页数由 ensurePagerTotals() 单独补（见 loadSongs），这里只管这一页的地形。
        pagerObserve('songs', page, songs.length, d.hasMore);
        markCurrentSong();
    } catch (err) {
        list.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
    } finally {
        songsLoading = false;
    }
}

// ============ 艺术家（分页） ============
// 每页 100 位（C# GetArtistsPage 的默认 pageSize），A-Z 跳转靠它把 offset 换算成页号。
const ARTIST_PAGE_SIZE = 100;
let artistsPage = 1;

async function loadArtists(page = 1) {
    artistsPage = page;
    showPage('artists');
    const grid = document.getElementById('artistsGrid');
    grid.innerHTML = skelGridHtml(8);
    pagerObserve('artists', page, 1, true);
    try {
        const d = await Bridge.data('getArtistsPage', page);
        renderArtistGroups(d.groups || []);
        const artists = d.artists || [];
        grid.innerHTML = artists.map(artistCardHtml).join('') || '<div class="status-line">暂无艺术家</div>';
        // 艺术家索引是完整取回的 ⇒ C# 给的是**确切**总页数，分页器因此一次就能画全。
        pagerObserve('artists', page, artists.length, d.hasMore, d.totalPages);
        initArtistLazyLoad();
    } catch (err) {
        grid.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
    }
}

/// 渲染艺术家卡片 HTML（头像惰性加载：优先艺术家照片，无则用代表专辑封面）
function artistCardHtml(a) {
    return `
        <div class="artist-card" data-artist-id="${esc(a.id)}">
            <div class="artist-avatar lazy-avatar" data-lazy-artist="${esc(a.id)}" data-artist-name="${esc(a.name)}" style="background:${artistColor(a.name)}">${esc(artistInitial(a.name))}</div>
            <div class="artist-name">${esc(a.name)}</div>
            <div class="artist-albums">${a.albumCount || 0} 张专辑</div>
        </div>`;
}

/// 艺术家头像惰性加载：滚动到可见时优先取艺术家照片（网易云），无则回退代表专辑封面。
let _artistObserver = null;
function initArtistLazyLoad() {
    if (!('IntersectionObserver' in window)) return;
    if (_artistObserver) _artistObserver.disconnect();
    _artistObserver = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (!entry.isIntersecting) return;
            const el = entry.target;
            const artistId = el.dataset.lazyArtist;
            const name = el.dataset.artistName || '';
            if (!artistId || el.dataset.loaded) return;
            el.dataset.loaded = '1';
            _artistObserver.unobserve(el);
            // 1) 优先网易云艺术家照片
            const setImg = (url) => {
                if (!url || !el.isConnected) return false;
                el.style.backgroundImage = `url("${url}")`;
                el.style.backgroundSize = 'cover';
                el.style.backgroundPosition = 'center';
                el.textContent = '';
                el.classList.add('has-image');
                return true;
            };
            const fallbackCover = () =>
                Bridge.dataQuiet('getArtistCover', artistId).then(d => setImg(d?.coverUrl)).catch(() => {});
            if (name) {
                Bridge.dataQuiet('getArtistPhoto', name).then(d => {
                    if (!setImg(d?.photoUrl)) fallbackCover();
                }).catch(fallbackCover);
            } else {
                fallbackCover();
            }
        });
    }, { rootMargin: '200px' });
    document.querySelectorAll('.lazy-avatar').forEach(el => _artistObserver.observe(el));
}

/// 渲染右侧 A-Z 字母导航条。
/// 字母跳转不再单独拉一段 offset 列表，而是换算成**页号**走统一分页，
/// 这样分页器的高亮页码和网格内容永远一致（不会出现「网格是 D 段、分页器写着第 1 页」）。
function renderArtistGroups(groups) {
    const bar = document.getElementById('artistAlpha');
    if (!bar) return;
    bar.innerHTML = groups.map(g => `
        <button class="alpha-btn" data-offset="${g.offset}" title="${esc(g.label)}">${esc(g.label || '?')}</button>`).join('');
    bar.querySelectorAll('.alpha-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const off = parseInt(btn.dataset.offset, 10) || 0;
            const page = Math.floor(off / ARTIST_PAGE_SIZE) + 1;
            const content = document.getElementById('content');
            if (content) content.scrollTop = 0;
            if (page === artistsPage) return;   // 已经在这一页，只回到顶部
            loadArtists(page);
        });
    });
}

/// 艺术家首字母（取第一个字符，空则用 ♪）
function artistInitial(name) {
    if (!name) return '♪';
    return name.charAt(0).toUpperCase();
}

/// 根据名字生成稳定的头像背景色
function artistColor(name) {
    if (!name) return '#2A2A33';
    const palette = ['#1E5F4A', '#3B5B7A', '#5A4A7A', '#7A5A3B', '#4A6A3B', '#6A3B5A', '#3B6A6A', '#5A5A3B'];
    let hash = 0;
    for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) >>> 0;
    return palette[hash % palette.length];
}

async function openArtistDetail(id, name) {
    showPage('artistDetail');
    const body = document.getElementById('artistDetailBody');
    body.innerHTML = skelDetailHtml();
    try {
        const a = await Bridge.data('getArtistDetail', id);
        if (!a) {
            // 兜底：只在歌曲的合作者里出现的艺术家可能不在艺术家索引里，按名字再搜一次。
            // 有名字才试，且要求搜到的 id 与本 id 不同（否则会自己递归自己）。
            if (name) {
                try {
                    const s = await Bridge.data('search', name);
                    const artists = s?.artists || [];
                    const hit = artists.find(x => x.name === name) || artists[0];
                    if (hit?.id && hit.id !== id) {
                        await openArtistDetail(hit.id, null);
                        return;
                    }
                } catch (e) { /* 搜索失败就走下面的提示 */ }
            }
            body.innerHTML = `<div class="status-line">找不到这个艺术家`
                + `${name ? '（' + esc(name) + '）' : ''} —— 它可能只作为合作者出现在某些歌曲里。</div>`;
            return;
        }
        // 用艺术家的代表封面做模糊背景（无则用专辑区首张封面，再不行网易云照片）
        // dataQuiet：封面只是装饰，不该让整个详情页顶着"正在加载"等它
        let coverUrl = '';
        try {
            const cv = await Bridge.dataQuiet('getArtistCover', id);
            coverUrl = cv?.coverUrl || '';
        } catch (e) { /* ignore */ }
        if (!coverUrl && (a.albums || []).length) coverUrl = a.albums[0].coverUrl || '';
        if (coverUrl) setDetailBg(coverUrl);
        const songs = a.songs || [];
        // 单曲区是**分页**的（一页 10 首），所以这里只渲染骨架和分页器，
        // 歌曲由 loadArtistSongs() 去填 —— 几百首的艺术家不该一次全渲染。
        currentArtistId = id;
        currentArtistName = a.name || name || '';
        const songsHtml = `<div class="section">
                <div class="section-header"><h2>单曲</h2><button class="action" data-play-list="artist">播放全部</button></div>
                <div class="song-list" id="artistSongsList">${songs.length
                    ? songs.map(s => songRowHtml(s, { linkArtist: false })).join('')
                    : '<div class="status-line">加载中...</div>'}</div>
                <div data-pager-host="artistSongs"></div>
              </div>`;
        body.innerHTML = `
            <div class="album-detail-meta" style="margin-bottom:20px">
                <h1>${esc(a.name)}</h1>
                <div class="sub">${a.albumCount || 0} 张专辑${songs.length ? ' · 共 ' + (a.songTotal || songs.length) + ' 首单曲' : ''}</div>
            </div>
            ${songsHtml}
            <div class="section">
                <div class="section-header"><h2>专辑</h2></div>
                <div class="album-grid">${(a.albums || []).map(albumCardHtml).join('') || '<div class="status-line">暂无专辑</div>'}</div>
            </div>`;
        // ArtistDetail 顺带返回的**就是第 1 页**（同一条扁平列表切出来的），所以这里只按
        // songPages 画分页器；只有算出多于 1 页时才需要再走分页接口去补后面的。
        PAGER.artistSongs = { page: 1, max: Math.max(1, a.songPages || 1), more: (a.songPages || 1) > 1,
                              load: p => loadArtistSongs(p) };
        if ((a.songPages || 1) > 1) {
            pagerObserve('artistSongs', 1, songs.length, true, a.songPages || 1);
        } else {
            pagerMount('artistSongs');
        }
        markCurrentSong();
    } catch (err) {
        body.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
    }
}

let currentArtistId = '';
let currentArtistName = '';

/// 艺术家页的某一页单曲（替换列表，不追加）。
async function loadArtistSongs(page = 1) {
    const list = document.getElementById('artistSongsList');
    if (!list || !currentArtistId) return;
    list.innerHTML = skelSongListHtml();
    try {
        const d = await Bridge.data('getArtistSongsPage', currentArtistId, page);
        if (d.status) { list.innerHTML = `<div class="status-line">${esc(d.status)}</div>`; return; }
        const songs = d.songs || [];
        // ★ 这里**不**给歌手名做链接：整页都是一个歌手，点它等于点当前这一页。
        list.innerHTML = songs.map(s => songRowHtml(s, { linkArtist: false })).join('')
            || '<div class="status-line">这一页是空的</div>';
        pagerObserve('artistSongs', page, songs.length, d.hasMore, d.totalPages);
        markCurrentSong();
    } catch (err) {
        list.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
    }
}

// ============ 收藏 / 历史 / 书签 ============
async function loadFavorites() {
    showPage('favorites');
    const list = document.getElementById('favoritesList');
    list.innerHTML = skelSongListHtml();
    try {
        const d = await Bridge.data('getFavorites');
        list.innerHTML = (d.songs || []).map(songRowHtml).join('') || '<div class="status-line">暂无收藏</div>';
    } catch (err) {
        list.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
    }
}

async function loadHistory() {
    showPage('history');
    const list = document.getElementById('historyList');
    list.innerHTML = skelSongListHtml();
    try {
        const d = await Bridge.data('getHistory');
        list.innerHTML = (d.songs || []).map(songRowHtml).join('') || '<div class="status-line">暂无历史</div>';
    } catch (err) {
        list.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
    }
}

async function loadBookmarks() {
    showPage('bookmarks');
    const list = document.getElementById('bookmarksList');
    list.innerHTML = skelSongListHtml();
    try {
        const d = await Bridge.data('getBookmarks');
        list.innerHTML = (d.bookmarks || []).map(b => `
            <div class="song-row" data-bookmark-id="${esc(b.id)}" data-song-id="${esc(b.id)}">
                <span class="song-index"><svg class="ic" width="14" height="14"><use href="#i-bookmark"/></svg></span>
                <span class="song-cover-wrap no-cover"><svg class="cover-fb" width="18" height="18"><use href="#i-music"/></svg></span>
                <span class="song-title">${esc(b.title)}</span>
                <span class="song-artist">${esc(b.artist || '')}</span>
                <span class="song-album">${esc(b.comment || '')}</span>
                <span class="song-duration">${esc(b.positionText || '')}</span>
                <span></span>
            </div>`).join('') || '<div class="status-line">暂无书签</div>';
    } catch (err) {
        list.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
    }
}

// ============ 正在播放 ============
let latestPlayback = null;

function renderNowPlaying() {
    showPage('nowPlaying');
    const body = document.getElementById('npFullBody');
    const s = latestPlayback || {};
    const isPlaying = !!s.isPlaying;
    // 只有切歌（songId 变化）时播放入场动画；点进页面/进度推送不重放，避免闪动
    const songChanged = (s.currentSongId || '') !== _lastNowPlayingSongId;
    _lastNowPlayingSongId = s.currentSongId || '';
    const anim = songChanged ? ' pack-enter' : '';
    const sleeveAnim = songChanged ? ' sleeve-enter' : '';
    const discAnim = songChanged ? ' disc-enter' : '';
    const titleAnim = songChanged ? ' title-enter' : '';
    body.innerHTML = `
        <div class="vinyl-pack${anim}">
            <!-- 封套：专辑封面作为外包装 -->
            <div class="vinyl-sleeve${sleeveAnim}">
                ${s.coverUrl
                    ? `<img class="sleeve-art" src="${s.coverUrl}" alt="" onerror="this.style.display='none';this.nextElementSibling.style.display='flex'">
                       <div class="sleeve-fb"><svg class="ic" width="44" height="44"><use href="#i-album"/></svg></div>`
                    : `<div class="sleeve-fb" style="display:flex"><svg class="ic" width="44" height="44"><use href="#i-music"/></svg></div>`}
            </div>
            <!-- 黑胶唱片（从封套右侧滑入，播放时旋转） -->
            <div class="vinyl-disc ${isPlaying ? 'spinning' : ''}${discAnim}">
                <div class="vinyl-grooves"></div>
                <div class="vinyl-hole"></div>
            </div>
        </div>
        <div class="np-full-title${titleAnim}">${esc(s.currentTitle || '未在播放')}</div>
        <div class="np-full-artist${titleAnim}">${esc(s.currentArtist || '')}</div>`;
    // 记录当前渲染的 key，避免 updatePlayerBar 重复重建导致闪烁
    _lastNowPlayingKey = (s.currentSongId || '') + '|' + (s.coverUrl || '');
}

// ============ 播放栏 ============
const playerBar = {
    title: document.getElementById('pbTitle'),
    artist: document.getElementById('pbArtist'),
    cover: document.getElementById('pbCover'),
    pos: document.getElementById('pbPos'),
    dur: document.getElementById('pbDur'),
    fill: document.getElementById('pbFill'),
    thumb: document.getElementById('pbThumb'),
    playBtn: document.getElementById('btnPlay'),
    fav: document.getElementById('pbFav'),
    modeBtn: document.getElementById('btnMode'),
    volume: document.getElementById('volumeSlider'),
    volumeVal: document.getElementById('volumeVal'),
    npTitle: document.getElementById('npTitle'),
    npArtist: document.getElementById('npArtist'),
    npCover: document.getElementById('npCover'),};

function updatePlayerBar(s) {
    if (!s) return;
    latestPlayback = s;
    // 起播确认：点的那首歌真的成为当前曲目了 → 撤掉行上的「转圈」
    if (_pendingPlayId !== null && s.currentSongId === _pendingPlayId) clearPlayPending();
    playerBar.title.textContent = s.currentTitle || '未在播放';
    // 底栏歌手名：知道 id 就渲染成可点链接（点了进该艺术家的单曲列表），否则纯文本。
    playerBar.artist.innerHTML = artistLinkHtml(s.currentArtist, s.currentArtistId);
    const pct = s.durationSeconds > 0 ? (s.positionSeconds / s.durationSeconds) * 100 : 0;
    playerBar.pos.textContent = fmtTime(s.positionSeconds);
    playerBar.dur.textContent = fmtTime(s.durationSeconds);
    playerBar.fill.style.width = pct + '%';
    playerBar.thumb.style.left = pct + '%';
    // 播放/暂停图标切换（SVG use，无闪烁）
    const playUse = document.querySelector('#btnPlayIcon use');
    if (playUse) playUse.setAttribute('href', s.isPlaying ? '#i-pause' : '#i-play');
    // 背景流光只在播放中流动（CSS 靠 body.is-playing 控制 animation-play-state）；
    // toggle 传 force，值没变时不会改 DOM，500ms 调一次也无成本。
    document.body.classList.toggle('is-playing', !!s.isPlaying);
    // 红心切换
    playerBar.fav.classList.toggle('active', !!s.isFavorite);
    const favUse = document.querySelector('#pbFavIcon use');
    if (favUse) favUse.setAttribute('href', s.isFavorite ? '#i-heart' : '#i-heart-outline');
    if (typeof s.volume === 'number') { playerBar.volume.value = s.volume; playerBar.volumeVal.textContent = Math.round(s.volume * 100) + '%'; }
    // 模式图标
    const modeUse = document.querySelector('#modeIcon use');
    if (modeUse) {
        const icons = { Sequence: '#i-repeat', Shuffle: '#i-shuffle', Repeat: '#i-repeat', RepeatOne: '#i-repeat-one' };
        modeUse.setAttribute('href', icons[s.playMode] || '#i-repeat');
        playerBar.modeBtn.classList.toggle('active', s.playMode === 'Repeat' || s.playMode === 'RepeatOne');
    }
    if (playerBar.npTitle) playerBar.npTitle.textContent = s.currentTitle || '未在播放';
    if (playerBar.npArtist) playerBar.npArtist.textContent = s.currentArtist || '';
    // 封面只在 URL 变化时更新，避免每 500ms 重复加载导致闪烁；无封面时显示占位
    if (s.coverUrl && s.coverUrl !== _lastCoverUrl) {
        _lastCoverUrl = s.coverUrl;
        playerBar.cover.style.display = '';
        const fb = document.querySelector('.pb-cover-fb');
        if (fb) fb.style.display = 'none';
        playerBar.cover.src = s.coverUrl;
        if (playerBar.npCover) playerBar.npCover.src = s.coverUrl;
        const bg = document.getElementById('bgImg');
        if (bg && !_detailOpen) bg.src = s.coverUrl;   // 动态封面底图跟随当前曲目（详情页由 setDetailBg 覆盖）
    } else if (!s.coverUrl) {
        // 始终处理无封面（含首次）
        _lastCoverUrl = null;
        playerBar.cover.style.display = 'none';
        const fb = document.querySelector('.pb-cover-fb');
        if (fb) fb.style.display = 'flex';
        playerBar.cover.removeAttribute('src');
        if (playerBar.npCover) playerBar.npCover.removeAttribute('src');
    }
    markCurrentSong();
    updateQueueHighlight();
    updateLyricHighlight();
    if (currentPage === 'nowPlaying') {
        const songKey = (s.currentSongId || '') + '|' + (s.coverUrl || '');
        if (songKey !== _lastNowPlayingKey) {
            _lastNowPlayingKey = songKey;
            renderNowPlaying();
        } else {
            // 仅更新旋转状态（无需重建 DOM，避免图片重载闪烁）
            const disc = document.querySelector('.vinyl-disc');
            if (disc) disc.classList.toggle('spinning', !!s.isPlaying);
        }
    }
}
let _lastCoverUrl = null;
let _lastNowPlayingKey = '';
let _lastNowPlayingSongId = '';

// 详情页封面背景：在专辑/艺术家详情页里，把全局模糊底图换成该内容的封面；离开详情恢复为正在播放的封面。
let _detailOpen = false;
function setDetailBg(url) {
    _detailOpen = true;
    const bg = document.getElementById('bgImg');
    if (bg) bg.src = url || '';
}
function clearDetailBg() {
    if (!_detailOpen) return;
    _detailOpen = false;
    const bg = document.getElementById('bgImg');
    if (bg) bg.src = _lastCoverUrl || '';
}

// ============ 事件绑定 ============
function initEvents() {
    playerBar.playBtn.addEventListener('click', () => Bridge.invoke('togglePlay'));
    document.getElementById('btnPrev').addEventListener('click', () => Bridge.invoke('previous'));
    document.getElementById('btnNext').addEventListener('click', () => Bridge.invoke('next'));
    playerBar.fav.addEventListener('click', () => Bridge.invoke('toggleFavorite'));

    playerBar.volume.addEventListener('input', () => {
        const v = parseFloat(playerBar.volume.value);
        playerBar.volumeVal.textContent = Math.round(v * 100) + '%';
        Bridge.invoke('setVolume', v);
    });
    playerBar.modeBtn.addEventListener('click', () => Bridge.invoke('togglePlayMode'));

    const track = document.getElementById('pbTrack');
    track.addEventListener('click', e => {
        const rect = track.getBoundingClientRect();
        const ratio = (e.clientX - rect.left) / rect.width;
        Bridge.invoke('seek', ratio);
    });

    // 底栏/迷你栏的歌手名（在 #content 之外，所以上面那条全局委托管不到它）。
    // 用 #playerBar 上的委托，而不是给每次重渲染出来的 span 单独绑，避免每 500ms 一次更新后监听器丢失。
    const barArtistHost = document.getElementById('playerBar');
    if (barArtistHost) {
        barArtistHost.addEventListener('click', e => {
            const el = e.target.closest('[data-artist-id]');
            if (!el) return;
            e.stopPropagation();
            openArtistDetail(el.dataset.artistId, el.dataset.artistName);
        });
    }

    document.getElementById('btnQueue').addEventListener('click', () => {
        if (sidePanelOpen && sidePanelMode === 'queue') closeSidePanel();
        else toggleQueuePanel();
    });
    document.getElementById('btnLyrics').addEventListener('click', () => {
        if (sidePanelOpen && sidePanelMode === 'lyrics') closeSidePanel();
        else toggleLyricsPanel();
    });
    document.getElementById('btnEq').addEventListener('click', () => {
        if (sidePanelOpen && sidePanelMode === 'eq') closeSidePanel();
        else toggleEqPanel();
    });
    document.getElementById('btnSleep').addEventListener('click', () => {
        if (sidePanelOpen && sidePanelMode === 'sleep') closeSidePanel();
        else toggleSleepMenu();
    });
    document.getElementById('btnMini').addEventListener('click', () => Bridge.invoke('openMiniPlayer'));

document.getElementById('settingsBtn').addEventListener('click', openSettingsModal);
document.getElementById('openSettingsBtn').addEventListener('click', openSettingsModal);

    const searchInput = document.getElementById('searchInput');
    searchInput.addEventListener('mousedown', e => e.stopPropagation()); // 防止 OSR 下焦点丢失
    searchInput.addEventListener('focus', () => Bridge.invoke('focusBrowser'));
    searchInput.addEventListener('keydown', e => {
        if (e.key === 'Enter') {
            // 顶栏输入同步到搜索页输入框
            const pi = document.getElementById('pageSearchInput');
            if (pi) pi.value = searchInput.value;
            navigate('search');
            runSearch(searchInput.value);
        }
    });

    // 搜索页输入框 + 按钮
    const pageSearchInput = document.getElementById('pageSearchInput');
    const pageSearchBtn = document.getElementById('pageSearchBtn');
    pageSearchInput.addEventListener('mousedown', e => e.stopPropagation());
    pageSearchInput.addEventListener('focus', () => Bridge.invoke('focusBrowser'));
    pageSearchInput.addEventListener('keydown', e => {
        if (e.key === 'Enter') runSearch(pageSearchInput.value);
    });
    pageSearchBtn.addEventListener('click', () => runSearch(pageSearchInput.value));

    // 窗口控制（HTML 自绘标题栏）
    document.getElementById('winMin').addEventListener('click', () => Bridge.invoke('windowMinimize'));
    document.getElementById('winMax').addEventListener('click', () => Bridge.invoke('windowMaximize'));
    document.getElementById('winClose').addEventListener('click', () => Bridge.invoke('windowClose'));

    // 标题栏拖动：mousedown 交给 C# Win32 拖动
    const titleBar = document.getElementById('titleBar');
    if (titleBar) {
        titleBar.addEventListener('mousedown', e => {
            // 只在空白区域/品牌区拖动，避免干扰搜索框/下拉/按钮
            if (e.target.closest('input, select, button, .search-box, .window-controls'))
                return;
            if (e.button === 0)
                Bridge.invoke('startWindowDrag');
        });
        titleBar.addEventListener('dblclick', e => {
            if (!e.target.closest('input, select, button'))
                Bridge.invoke('windowMaximize');
        });
    }

    // 返回按钮
    document.getElementById('albumBack').addEventListener('click', () => navigate('albums'));
    document.getElementById('playlistBack').addEventListener('click', () => navigate('playlists'));
    document.getElementById('genreBack').addEventListener('click', () => navigate('genres'));
    document.getElementById('artistBack').addEventListener('click', () => navigate('artists'));

    // 全局委托：艺术家名字 / 专辑卡 / 歌曲行 / 播放列表 / 收藏按钮
    document.getElementById('content').addEventListener('click', async e => {
        // 分页器最先判断：它在列表末尾，但里面的按钮和"行"的点击语义完全不同。
        const pagerBtn = e.target.closest('[data-pager][data-page]');
        if (pagerBtn) {
            e.stopPropagation();
            pagerGo(pagerBtn.dataset.pager, parseInt(pagerBtn.dataset.page, 10));
            return;
        }
        // ★ 艺术家名字排在最前面。它出现在专辑卡、歌曲行、专辑详情里，
        // 而那些容器本身也各有点击行为（开专辑、播这首）——先判断最内层的链接，
        // 否则点歌手名字会变成"打开这张专辑"或"播放这首"。
        const artistLink = e.target.closest('[data-artist-id]');
        if (artistLink) {
            e.stopPropagation();
            openArtistDetail(artistLink.dataset.artistId, artistLink.dataset.artistName);
            return;
        }
        const albumCard = e.target.closest('.album-card');
        if (albumCard && albumCard.dataset.id) {
            openAlbumDetail(albumCard.dataset.id);
            return;
        }
        const playlistCard = e.target.closest('.album-card[data-playlist-id]');
        if (playlistCard) { openPlaylistDetail(playlistCard.dataset.playlistId); return; }

        const playBtn = e.target.closest('[data-play-album]');
        if (playBtn) { playOnce('playAlbum:' + playBtn.dataset.playAlbum, () => Bridge.invoke('playAlbum', playBtn.dataset.playAlbum)); return; }

        const playList = e.target.closest('[data-play-list]');
        if (playList) {
            const listEl = playList.closest('.section').querySelector('.song-list');
            const songs = collectSongs(listEl);
            if (songs.length) {
                markPlayPending(songs[0].id);
                playOnce('playList:' + songs.map(s => s.id).join(','), () => Bridge.invoke('playSongsJson', JSON.stringify(songs), 0));
            }
            return;
        }

        const favBtn = e.target.closest('[data-fav]');
        if (favBtn) {
            e.stopPropagation();
            Bridge.invoke('toggleFavoriteForSong', favBtn.dataset.fav);
            return;
        }

        const songRow = e.target.closest('.song-row');
        if (songRow) {
            // 书签行：从书签位置播放
            if (songRow.dataset.bookmarkId) {
                markPlayPending(songRow.dataset.songId);
                playOnce('bookmark:' + songRow.dataset.songId, () => Bridge.invoke('playBookmark', songRow.dataset.songId || '', 0));
                return;
            }
            const listEl = songRow.closest('.song-list');
            const songs = collectSongs(listEl);
            const start = songs.findIndex(s => s.id === songRow.dataset.id);
            if (songs.length) {
                markPlayPending(songRow.dataset.id);   // 点下立刻给反馈，不等 C# 建流
                // 诊断：确认点击确实发出去了、发给第几首（对应 bridge.log 的 PlaySongsJson 行）
                try { Bridge.invoke('logClient', `clickSong id=${songRow.dataset.id} start=${Math.max(0, start)} count=${songs.length}`); } catch (e) { }
                playOnce('song:' + songRow.dataset.id, () => Bridge.invoke('playSongsJson', JSON.stringify(songs), Math.max(0, start)));
            }
        }
    });
}

// ============ 搜索 ============
async function runSearch(query) {
    showPage('search');
    const body = document.getElementById('searchBody');
    body.innerHTML = skelSongListHtml();
    try {
        const d = await Bridge.data('search', query);
        let html = '';
        if (d.songs?.length) {
            html += `<div class="section"><div class="section-header"><h2>歌曲 (${d.songs.length})</h2></div><div class="song-list">${d.songs.map(songRowHtml).join('')}</div></div>`;
        }
        if (d.albums?.length) {
            html += `<div class="section"><div class="section-header"><h2>专辑 (${d.albums.length})</h2></div><div class="album-grid">${d.albums.map(albumCardHtml).join('')}</div></div>`;
        }
        if (d.artists?.length) {
            html += `<div class="section"><div class="section-header"><h2>艺术家 (${d.artists.length})</h2></div><div class="artist-grid">${d.artists.map(a => `
                <div class="artist-card" data-artist-id="${esc(a.id)}">
                    <div class="artist-avatar" style="background:${artistColor(a.name)}">${esc(artistInitial(a.name))}</div>
                    <div class="artist-name">${esc(a.name)}</div>
                </div>`).join('')}</div></div>`;
        }
        body.innerHTML = html || `<div class="status-line">未找到「${esc(query)}」相关结果</div>`;
    } catch (err) {
        body.innerHTML = `<div class="status-line">搜索失败: ${esc(err.message)}</div>`;
    }
}

// ============ 服务列表 ============
// OSR（离屏渲染）下原生 <select> 的弹窗列表不渲染，改用自绘下拉（与主题菜单一致），
// 这样点开才能看到服务器列表。renderServices 负责填充菜单按钮 + 回显当前服务名。
function renderServices(services, currentId) {
    const menu = document.getElementById('serviceSelectMenu');
    const valSpan = document.getElementById('serviceSelectValue');
    const list = (services || []);
    if (menu) {
        menu.innerHTML = '';
        list.forEach(s => {
            const item = document.createElement('button');
            item.className = 'service-item' + (s.id === currentId ? ' active' : '');
            item.dataset.svc = s.id;
            item.textContent = s.name;
            item.addEventListener('click', () => {
                closeServiceSelect();
                if (s.id !== currentId) Bridge.invoke('switchService', s.id);
            });
            menu.appendChild(item);
        });
    }
    if (valSpan) {
        const cur = list.find(s => s.id === currentId);
        valSpan.textContent = cur ? cur.name : (list.length ? list[0].name : '服务器');
    }
}

/// 自绘服务器下拉：点按钮开合，点外部关闭。
function initServiceSelect() {
    const btn = document.getElementById('serviceSelectBtn');
    const menu = document.getElementById('serviceSelectMenu');
    if (btn && menu) {
        btn.addEventListener('click', e => {
            e.stopPropagation();
            menu.setAttribute('data-open', menu.getAttribute('data-open') === 'true' ? 'false' : 'true');
        });
        document.addEventListener('mousedown', e => {
            if (menu.getAttribute('data-open') === 'true' && !menu.contains(e.target) && !btn.contains(e.target)) {
                closeServiceSelect();
            }
        });
    }
}

function closeServiceSelect() {
    const menu = document.getElementById('serviceSelectMenu');
    if (menu) menu.setAttribute('data-open', 'false');
}

// ============ 设置弹窗 ============
let _editingSvcId = null;

async function openSettingsModal() {
    const modal = document.getElementById('settingsModal');
    modal.style.display = 'flex';
    _editingSvcId = null;
    await renderServiceList();
    _clearSvcForm();
}

function closeSettingsModal() {
    document.getElementById('settingsModal').style.display = 'none';
}

async function renderServiceList() {
    const list = document.getElementById('serviceList');
    const data = await Bridge.invoke('getServices');
    if (!data?.services) { list.innerHTML = '<div class="status-line">无法加载</div>'; return; }
    list.innerHTML = data.services.map(s => `
        <div class="svc-item ${s.id === data.currentServiceId ? 'current' : ''}">
            <div>
                <div class="svc-name">${esc(s.name)}</div>
                <div class="svc-sub">${esc(s.lanUrl || s.wanUrl || '')}</div>
            </div>
            <span class="svc-type">${esc(s.type || 'Subsonic')}</span>
            <div style="display:flex;gap:4px">
                <button class="svc-edit" data-svc-edit="${esc(s.id)}" title="编辑">✎</button>
                <button class="svc-del" data-svc-del="${esc(s.id)}" title="删除">🗑</button>
            </div>
        </div>`).join('');
    // 绑定事件
    list.querySelectorAll('.svc-item').forEach(item => {
        const id = item.querySelector('.svc-edit').dataset.svcEdit;
        item.addEventListener('click', () => {
            if (!item.querySelector('.svc-del') || item.querySelector('.svc-del').dataset.svcDel !== id) return;
            Bridge.invoke('switchService', id);
            renderServiceList();
        });
    });
    list.querySelectorAll('[data-svc-edit]').forEach(btn => {
        btn.addEventListener('click', async e => {
            e.stopPropagation();
            const svc = data.services.find(s => s.id === btn.dataset.svcEdit);
            if (!svc) return;
            _editingSvcId = svc.id;
            document.getElementById('svcName').value = svc.name || '';
            document.getElementById('svcType').value = svc.type || 'Subsonic';
            document.getElementById('svcLan').value = svc.lanUrl || '';
            document.getElementById('svcWan').value = svc.wanUrl || '';
            document.getElementById('svcUser').value = svc.username || '';
            document.getElementById('svcPass').value = '';
            document.getElementById('svcPass').placeholder = svc.hasPassword ? '已保存（留空不修改）' : '密码';
            document.getElementById('svcFormTitle').textContent = '编辑服务器';
        });
    });
    list.querySelectorAll('[data-svc-del]').forEach(btn => {
        btn.addEventListener('click', async e => {
            e.stopPropagation();
            await Bridge.invoke('deleteService', btn.dataset.svcDel);
            await renderServiceList();
            const d = await Bridge.invoke('getServices');
            renderServices(d?.services, d?.currentServiceId);
        });
    });
}

function _clearSvcForm() {
    document.getElementById('svcName').value = '';
    document.getElementById('svcType').value = 'Subsonic';
    document.getElementById('svcLan').value = '';
    document.getElementById('svcWan').value = '';
    document.getElementById('svcUser').value = '';
    document.getElementById('svcPass').value = '';
    document.getElementById('svcPass').placeholder = '密码';
    document.getElementById('svcFormTitle').textContent = '新增服务器';
}

function initSettingsModalEvents() {
    document.getElementById('settingsClose').addEventListener('click', closeSettingsModal);
    document.getElementById('queueClose').addEventListener('click', closeSidePanel);
    // 音量平均开关：变更即持久化并应用到引擎
    const vn = document.getElementById('volNormToggle');
    if (vn) vn.addEventListener('change', () => Bridge.invoke('setVolumeNormalization', vn.checked));
    document.getElementById('svcSave').addEventListener('click', async () => {
        const id = _editingSvcId || 'svc_' + Date.now();
        await Bridge.invoke('saveService', id,
            document.getElementById('svcName').value,
            document.getElementById('svcType').value,
            document.getElementById('svcLan').value,
            document.getElementById('svcWan').value,
            document.getElementById('svcUser').value,
            document.getElementById('svcPass').value);
        _editingSvcId = null;
        await renderServiceList();
        _clearSvcForm();
        const data = await Bridge.invoke('getServices');
        renderServices(data?.services, data?.currentServiceId);
    });
    document.getElementById('svcNew').addEventListener('click', () => {
        _editingSvcId = null;
        _clearSvcForm();
    });
    // 点击遮罩关闭
    document.getElementById('settingsModal').addEventListener('click', e => {
        if (e.target.id === 'settingsModal') closeSettingsModal();
    });
}

// ============ 播放队列 / 歌词 / EQ / 睡眠 侧面板 ============
let sidePanelOpen = false;
let sidePanelMode = 'queue'; // 'queue' | 'lyrics' | 'eq' | 'sleep'

/// 关闭侧面板（队列/歌词/EQ/睡眠通用）
function closeSidePanel() {
    if (!sidePanelOpen) return;
    sidePanelOpen = false;
    const panel = document.getElementById('queuePanel');
    panel.style.right = '-340px';
    panel.style.visibility = 'hidden';
}

// 点击侧面板外空白区域关闭（EQ 等所有模式通用）；点击打开按钮本身不触发
document.addEventListener('mousedown', e => {
    if (!sidePanelOpen) return;
    const panel = document.getElementById('queuePanel');
    const onPanel = panel.contains(e.target);
    const onOpener = e.target.closest('#btnQueue, #btnLyrics, #btnEq, #btnSleep');
    if (!onPanel && !onOpener) closeSidePanel();
});

async function toggleQueuePanel() {
    sidePanelMode = 'queue';
    document.querySelector('#queuePanel h3').textContent = '播放队列';
    await openSidePanel();
}

async function toggleLyricsPanel() {
    sidePanelMode = 'lyrics';
    document.querySelector('#queuePanel h3').textContent = '歌词';
    await openSidePanel();
}

async function toggleEqPanel() {
    sidePanelMode = 'eq';
    document.querySelector('#queuePanel h3').textContent = '均衡器';
    await openSidePanel();
}

/// 侧面板的"静止位置" = CSS 里的 --overlay-gap（浮层离窗口右边一条缝）。
/// 注意读的是 **--overlay-gap** 而不是 --panel-gap：后者是"三块主面板之间的缝"，
/// 用户要求中间粘边时它是 0，浮层不能跟着变成 0（那样就贴死在窗口边上了）。
function panelGapPx() {
    const v = getComputedStyle(document.documentElement).getPropertyValue('--overlay-gap').trim();
    return v || '10px';
}

async function openSidePanel() {
    const panel = document.getElementById('queuePanel');
    sidePanelOpen = true;
    panel.style.right = panelGapPx();
    panel.style.visibility = 'visible';
    const body = document.getElementById('queueBody');
    if (sidePanelMode === 'queue') await renderQueue();
    else if (sidePanelMode === 'lyrics') await renderLyrics();
    else await renderEq();
}

async function renderEq() {
    const body = document.getElementById('queueBody');
    const freqs = [100, 150, 250, 500, 1000, 2000, 4000, 8000, 12000, 16000];
    const presets = ['关闭', '摇滚', '流行', '古典', '人声', '重低音'];
    // 读取当前 EQ 增益（关闭面板后重开保持上次调整）
    let initGains = [];
    try { initGains = await Bridge.invoke('getEqGains'); } catch (e) { }
    if (!Array.isArray(initGains)) initGains = [];
    body.innerHTML = `
        <div class="eq-pro">
            <!-- 预设 -->
            <div class="eq-presets">
                ${presets.map(p => `<button class="eq-preset-btn" data-preset="${p}">${p}</button>`).join('')}
            </div>
            <!-- 滑块区 -->
            <div class="eq-sliders">
                ${freqs.map((f, i) => {
                    const g = initGains[i] || 0;
                    return `
                    <div class="eq-band">
                        <span class="eq-db" id="eqDb${i}" style="color:${g === 0 ? 'var(--text-muted)' : (g > 0 ? '#EF4444' : '#3B82F6')}">${g > 0 ? '+' : ''}${g}</span>
                        <div class="eq-slider-track">
                            <input type="range" class="eq-slider" data-band="${i}" min="-12" max="12" step="0.5" value="${g}" orient="vertical">
                        </div>
                        <span class="eq-freq">${f >= 1000 ? (f/1000).toFixed(0) + 'K' : f}</span>
                    </div>`;
                }).join('')}
            </div>
            <!-- 频段标签 -->
            <div class="eq-scale">
                <span>+12</span><span>0</span><span>-12</span>
            </div>
        </div>`;
    body.querySelectorAll('.eq-slider').forEach(slider => {
        slider.addEventListener('input', () => {
            const band = parseInt(slider.dataset.band);
            const val = parseFloat(slider.value);
            const dbEl = document.getElementById('eqDb' + band);
            if (dbEl) dbEl.textContent = (val > 0 ? '+' : '') + val;
            dbEl.style.color = val === 0 ? 'var(--text-muted)' : (val > 0 ? '#EF4444' : '#3B82F6');
            Bridge.invoke('setEqGain', band, val);
        });
        // 双击复位归零
        slider.addEventListener('dblclick', () => {
            slider.value = 0;
            slider.dispatchEvent(new Event('input'));
        });
    });
    // 预设
    body.querySelectorAll('.eq-preset-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const preset = btn.dataset.preset;
            body.querySelectorAll('.eq-preset-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            if (preset === '关闭') { Bridge.invoke('resetEq'); }
            else { Bridge.invoke('applyEqPreset', preset); }
            // 更新滑块位置显示
            const gains = presetGains(preset);
            body.querySelectorAll('.eq-slider').forEach((slider, i) => {
                slider.value = gains[i];
                const dbEl = document.getElementById('eqDb' + i);
                if (dbEl) dbEl.textContent = (gains[i] > 0 ? '+' : '') + gains[i];
            });
        });
    });
    // 面板重开时：按当前增益匹配预设，恢复选中状态
    if (initGains.length) {
        let matched = '关闭';
        for (const p of presets) {
            if (p === '关闭') continue;
            const g = presetGains(p);
            if (g.length === initGains.length && g.every((v, i) => Math.abs(v - initGains[i]) < 0.01)) { matched = p; break; }
        }
        if (matched === '关闭' && initGains.some(v => Math.abs(v) > 0.01)) matched = null; // 手动调整过，无预设匹配
        if (matched) {
            body.querySelectorAll('.eq-preset-btn').forEach(b => b.classList.toggle('active', b.dataset.preset === matched));
        }
    }
}

/// 预设 gain 值（与 C# 一致，用于滑块位置回显）
function presetGains(name) {
    switch (name) {
        case '摇滚': return [5, 3, 0, -2, -1, 2, 4, 5, 4, 3];
        case '流行': return [-1, 1, 3, 4, 3, 0, -1, -1, 0, 1];
        case '古典': return [4, 3, 2, 0, -1, -1, 0, 2, 3, 4];
        case '人声': return [-2, -1, 0, 2, 4, 4, 3, 1, 0, -1];
        case '重低音': return [6, 5, 4, 2, 0, 0, 0, 0, 0, 0];
        default: return [0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
    }
}

// 睡眠定时器选项
async function toggleSleepMenu() {
    const panel = document.getElementById('queuePanel');
    sidePanelMode = 'sleep';
    document.querySelector('#queuePanel h3').textContent = '睡眠定时器';
    sidePanelOpen = true;
    panel.style.right = panelGapPx();
    panel.style.visibility = 'visible';
    const body = document.getElementById('queueBody');
    body.innerHTML = `
        <div class="sleep-view">
            <div class="sleep-tip" id="sleepTip">播放到设定时间后自动暂停</div>
            <button class="btn-primary sleep-opt" data-min="15" onclick="pickSleep(15)">15 分钟</button>
            <button class="btn-primary sleep-opt" data-min="30" onclick="pickSleep(30)">30 分钟</button>
            <button class="btn-primary sleep-opt" data-min="60" onclick="pickSleep(60)">60 分钟</button>
            <button class="btn-ghost sleep-opt" data-min="0" onclick="pickSleep(0)">关闭定时器</button>
        </div>`;
}

/// 设置睡眠定时器并给出面板反馈（C# 真正计时，这里同步高亮提示）
function pickSleep(min) {
    Bridge.invoke('setSleepTimer', min);
    document.querySelectorAll('.sleep-opt').forEach(b => b.classList.toggle('active', parseInt(b.dataset.min) === min));
    const tip = document.getElementById('sleepTip');
    if (tip) tip.textContent = min > 0 ? `已设置 ${min} 分钟睡眠定时器` : '睡眠定时器已关闭';
}

async function renderQueue() {
    const body = document.getElementById('queueBody');
    const data = await Bridge.data('getQueue');
    const songs = data?.songs || [];
    if (!songs.length) { body.innerHTML = '<div class="status-line" style="padding:20px">队列为空</div>'; return; }
    const cur = data.currentIndex ?? -1;
    body.innerHTML = songs.map((s, i) => `
        <div class="queue-item ${i === cur ? 'current' : ''}" data-qidx="${i}" data-song-id="${esc(s.id)}">
            <span class="qi-index">${i === cur ? '<svg width="12" height="12" class="ic"><use href="#i-now"/></svg>' : (i + 1)}</span>
            ${s.coverUrl
                ? `<img class="qi-cover" src="${s.coverUrl}" alt="" onerror="this.outerHTML='<span class=&quot;qi-cover&quot;><svg width=&quot;16&quot; height=&quot;16&quot;><use href=&quot;#i-music&quot;/></svg></span>'">`
                : `<span class="qi-cover"><svg width="16" height="16"><use href="#i-music"/></svg></span>`}
            <div>
                <div class="qi-title">${esc(s.title)}</div>
                <div class="qi-artist">${artistLinkHtml(s.artist, s.artistId)}</div>
            </div>
            <span class="qi-dur">${esc(s.durationText || '')}</span>
        </div>`).join('');
    body.querySelectorAll('.queue-item').forEach(item => {
        item.addEventListener('click', e => {
            // 点歌手名字 = 去那个歌手，不是播放这一行。队列行的整体点击是"播这首"，
            // 所以这里必须先拦下来，否则两个动作会同时发生（面板还会挡着歌手页）。
            const artistEl = e.target.closest('[data-artist-id]');
            if (artistEl) {
                e.stopPropagation();
                closeSidePanel();
                openArtistDetail(artistEl.dataset.artistId, artistEl.dataset.artistName);
                return;
            }
            const qidx = parseInt(item.dataset.qidx);
            // 队列行也给即时反馈（队列点击走 PlayFromIndex → 同样要建流）
            markPlayPending(item.dataset.songId);
            item.classList.add('play-pending');
            const qiIdx = item.querySelector('.qi-index');
            if (qiIdx) qiIdx.innerHTML = '<span class="play-spinner"></span>';
            playOnce('queue:' + qidx, () => Bridge.invoke('playFromQueue', qidx));
        });
    });

    // 滚动到当前播放项，使其居中显示
    const currentItem = body.querySelector('.queue-item.current');
    if (currentItem && body.scrollHeight > body.clientHeight) {
        const targetY = currentItem.offsetTop - (body.clientHeight - currentItem.offsetHeight) / 2;
        body.scrollTop = Math.max(0, targetY);
    }
}

/// 更新队列面板的当前项高亮（播放切换时调用，无需整体重渲染）
function updateQueueHighlight() {
    if (!sidePanelOpen || sidePanelMode !== 'queue') return;
    const s = latestPlayback;
    if (!s || !s.currentSongId) return;
    const body = document.getElementById('queueBody');
    // 找到当前播放歌曲对应的行
    body.querySelectorAll('.queue-item').forEach((item, i) => {
        // 用歌曲 id 判断：需要 row 上存 song id
        const isCurrent = item.dataset.songId === s.currentSongId;
        item.classList.toggle('current', isCurrent);
        const idxEl = item.querySelector('.qi-index');
        if (idxEl) idxEl.innerHTML = isCurrent
            ? '<svg width="12" height="12" class="ic"><use href="#i-now"/></svg>'
            : (i + 1);
    });
}

async function renderLyrics() {
    const body = document.getElementById('queueBody');
    const data = await Bridge.data('getCurrentLyrics');
    if (!data?.hasLyrics) {
        body.innerHTML = '<div class="status-line" style="padding:20px">暂无歌词</div>';
        return;
    }
    if (data.isSynced && data.lines?.length) {
        body.innerHTML = `<div class="lyrics-view">${data.lines.map(l => `
            <div class="lyric-line" data-start="${l.start}">${esc(l.text)}</div>`).join('')}</div>`;
        updateLyricHighlight();
    } else {
        body.innerHTML = `<div class="lyrics-view"><div class="lyric-plain">${esc(data.text || '暂无歌词')}</div></div>`;
    }
}

/// 卡拉 OK 歌词：根据播放进度高亮当前行并滚动到面板中央。
function updateLyricHighlight() {
    if (!sidePanelOpen || sidePanelMode !== 'lyrics') return;
    const body = document.getElementById('queueBody');
    const lines = body.querySelectorAll('.lyric-line');
    if (!lines.length) return;
    const pos = (latestPlayback && latestPlayback.positionSeconds) || 0;
    let cur = 0;
    for (let i = 0; i < lines.length; i++) {
        if (parseFloat(lines[i].dataset.start) <= pos) cur = i;
        else break;
    }
    lines.forEach((el, i) => el.classList.toggle('current', i === cur));
    const currentEl = lines[cur];
    if (currentEl) {
        const target = currentEl.offsetTop - (body.clientHeight - currentEl.offsetHeight) / 2;
        body.scrollTop = Math.max(0, target);
    }
}

// ============ 初始化 ============
async function init() {
    // 主题/强调色常量必须先初始化（TDZ：后续函数在被调用时会读到它们）
    const THEMES = {
        dark:     { name: '深邃黑', isLight: false, dot: '#2DD4A7' },
        light:    { name: '月光白', isLight: true,  dot: '#F5F5F7' },
        forest:   { name: '森林绿', isLight: false, dot: '#34D399' },
        midnight: { name: '午夜蓝', isLight: false, dot: '#60A5FA' },
        sunset:   { name: '落日橙', isLight: false, dot: '#FB923C' },
        rose:     { name: '玫瑰紫', isLight: false, dot: '#F472B6' },
    };
    const THEME_ORDER = ['dark', 'light', 'forest', 'midnight', 'sunset', 'rose'];
    let currentTheme = 'dark';
    const ACCENT_THEMES = {
        teal:   { name:'青绿', dark:{accent:'#2DD4A7',soft:'rgba(45,212,167,0.14)',text:'#5EEAD4',dim:'#15803D'}, light:{accent:'#0FAE85',soft:'rgba(15,174,133,0.14)',text:'#0B6B4F',dim:'#0E7490'} },
        purple: { name:'紫',   dark:{accent:'#A78BFA',soft:'rgba(167,139,250,0.14)',text:'#C4B5FD',dim:'#6D28D9'}, light:{accent:'#7C3AED',soft:'rgba(124,58,237,0.14)',text:'#5B21B6',dim:'#6D28D9'} },
        rose:   { name:'玫红', dark:{accent:'#F472B6',soft:'rgba(244,114,182,0.14)',text:'#F9A8D4',dim:'#BE185D'}, light:{accent:'#DB2777',soft:'rgba(219,39,119,0.14)',text:'#9D174D',dim:'#BE185D'} },
        amber:  { name:'琥珀', dark:{accent:'#FBBF24',soft:'rgba(251,191,36,0.14)',text:'#FCD34D',dim:'#B45309'}, light:{accent:'#D97706',soft:'rgba(217,119,6,0.14)',text:'#92400E',dim:'#B45309'} },
        blue:   { name:'蓝',   dark:{accent:'#60A5FA',soft:'rgba(96,165,250,0.14)',text:'#93C5FD',dim:'#1D4ED8'}, light:{accent:'#2563EB',soft:'rgba(37,99,235,0.14)',text:'#1E40AF',dim:'#1D4ED8'} },
    };

    initNav();
    initEvents();
    initServiceSelect();
    initSettingsModalEvents();
    initDiscoverEvents();
    initAccentThemes();
    initThemeControls();

    try {
        const initial = await Bridge.invoke('getInitialState');
        if (initial) {
            updatePlayerBar(initial.playback);
            renderServices(initial.services, initial.currentServiceId);
            // 版本号（设置弹窗底部显示，便于核对是否最新版）
            const av = document.getElementById('appVersion');
            if (av && initial.version) av.textContent = 'v' + initial.version;
            // 恢复持久化的主题（深浅只是其中两套）
            if (initial.theme) applyTheme(initial.theme);
            // 音量平均开关初始值
            const vn = document.getElementById('volNormToggle');
            if (vn && typeof initial.volumeNormalization === 'boolean') vn.checked = initial.volumeNormalization;
        }
    } catch (err) {
        console.error('获取初始状态失败:', err);
    }

    // 建流失败（服务端缺文件 404 / 无法流式 / 网络中断）→ 明确告诉用户，
    // 而不是让界面停在上一首歌上、用户以为"点了没反应"。
    StateBridge.on('playbackError', s => {
        if (!s) return;
        clearPlayPending();
        showToast(`播放失败：${s.title || ''} ${s.message || '原因未知'}`.trim(), 7000);
    });

    // 诊断：前端到底有没有收到"换曲目"的 playback 事件（只在曲目 id 变化时记一条）
    let _lastRecvSongId = null;
    StateBridge.on('playback', s => {
        if (s && s.currentSongId !== _lastRecvSongId) {
            _lastRecvSongId = s.currentSongId;
            try { Bridge.invoke('logClient', `recvPlayback id=${s.currentSongId || '-'} title=${s.currentTitle || '-'} isPlaying=${s.isPlaying}`); } catch (e) { }
        }
        updatePlayerBar(s);
    });
    StateBridge.on('services', s => {
        renderServices(s.services, s.currentServiceId);
        // 服务配置变更/切换后：清空数据缓存并重新加载当前页面，确保新配置的数据立即生效
        for (const k of Object.keys(pageCache)) delete pageCache[k];
        for (const k of Object.keys(albumsCache)) delete albumsCache[k];
        for (const k of Object.keys(songsCache)) delete songsCache[k];
        // 分页器状态也要归零：换服务器后"已知最远的一页"是上一台服务器的地形，
        // 留着它会让新服务器上出现一个点了就是空页的「下一页」。
        for (const k of Object.keys(PAGER)) PAGER[k] = { page: 1, max: 1, more: true, load: PAGER[k].load };
        _pagerTotalsDone = false;   // 换服务器 ⇒ 曲库规模也要重算
        // 风格页也按服务器区分：清掉已渲染的网格，切服务器后重新拉取
        const gg = document.getElementById('genresGrid');
        if (gg) gg.innerHTML = '';
        const gd = document.getElementById('genreDetailBody');
        if (gd) gd.innerHTML = '';
        // 发现页必须强制重载：清掉「已加载」标记（否则 loadDiscover 直接 return）+
        // 当天 localStorage 缓存 + 智能推荐的复用数据，否则切服务器后显示的还是旧服务器内容。
        discoverTabLoaded = false;
        discoverMoreData = null;
        try { clearTabCache('random'); clearTabCache('smart'); } catch (e) { /* ignore */ }
        if (typeof currentPage === 'string' && currentPage) navigate(currentPage);
    });
    // ---- 主题预设（完整配色；「深浅」只是其中两套，不再是独立开关）----
    // 每个主题 = 一套完整 CSS 变量（背景/文字/边框/强调/玻璃全部 --*），CSS 端按 html[data-theme] 定义。
    // THEMES / THEME_ORDER / currentTheme / ACCENT_THEMES 已在 init() 顶部初始化（避免 TDZ）。

    /// 换一套主题：切换 html[data-theme]，并按是否选过强调色决定强调色的取用。
    function applyTheme(id) {
        if (!THEMES[id]) id = 'dark';
        currentTheme = id;
        document.documentElement.setAttribute('data-theme', id);
        // 有强调色则覆盖主题默认强调色，否则清除内联强调色、让主题自带强调色生效
        if (typeof applyAccentTheme === 'function') applyAccentTheme();
        syncThemeUI();
    }

    /// 用户主动选主题：即时应用 + 持久化到 C# AppSettings（ThemeId）。
    function selectTheme(id) {
        applyTheme(id);
        try { Bridge.invoke('setTheme', id); } catch (e) { /* 页面未就绪忽略 */ }
    }

    /// 同步顶栏菜单 + 设置下拉的选中态。
    function syncThemeUI() {
        const sel = document.getElementById('themeSelect');
        if (sel) sel.value = currentTheme;
        document.querySelectorAll('.theme-item').forEach(b => {
            b.classList.toggle('active', b.dataset.theme === currentTheme);
        });
    }

    function applyAccentTheme() {
        try {
            const st = document.documentElement.style;
            const key = localStorage.getItem('accentTheme');
            if (key && ACCENT_THEMES[key]) {
                const t = ACCENT_THEMES[key];
                const c = t[currentTheme === 'light' ? 'light' : 'dark'];
                st.setProperty('--accent', c.accent);
                st.setProperty('--accent-soft', c.soft);
                st.setProperty('--accent-text', c.text);
                st.setProperty('--accent-dim', c.dim);
            } else {
                st.removeProperty('--accent');
                st.removeProperty('--accent-soft');
                st.removeProperty('--accent-text');
                st.removeProperty('--accent-dim');
            }
            document.querySelectorAll('#accentThemes .accent-swatch').forEach(b => {
                b.classList.toggle('active', key === b.dataset.accent);
            });
        } catch (e) {
            // localStorage 在 app:// 协议下可能不可用（SecurityError）——静默跳过，绝不断掉页面初始化
        }
    }
    function initAccentThemes() {
        const wrap = document.getElementById('accentThemes');
        if (!wrap) return;
        try { applyAccentTheme(); } catch (e) { /* ignore */ }
        wrap.addEventListener('click', e => {
            const btn = e.target.closest('.accent-swatch');
            if (!btn) return;
            try { localStorage.setItem('accentTheme', btn.dataset.accent); } catch (e) { /* ignore */ }
            applyAccentTheme();
        });
    }

    /// 顶栏主题菜单 + 设置下拉：列出全部主题，选即应用并持久化。
    function initThemeControls() {
        // 顶栏下拉菜单
        const menu = document.getElementById('themeMenu');
        const menuBtn = document.getElementById('themeMenuBtn');
        if (menu && menuBtn) {
            // 生成主题项
            const header = document.createElement('div');
            header.className = 'theme-header';
            header.textContent = '主题';
            menu.appendChild(header);
            THEME_ORDER.forEach(id => {
                const t = THEMES[id];
                const item = document.createElement('button');
                item.className = 'theme-item';
                item.dataset.theme = id;
                item.innerHTML = `<span class="tdot" style="background:${t.dot}"></span><span class="tname">${t.name}</span><span class="tcheck">✓</span>`;
                item.addEventListener('click', () => {
                    closeThemeMenu();
                    selectTheme(id);
                });
                menu.appendChild(item);
            });
            menuBtn.addEventListener('click', e => {
                e.stopPropagation();
                const open = menu.getAttribute('data-open') === 'true';
                menu.setAttribute('data-open', open ? 'false' : 'true');
            });
            // 点击菜单外关闭
            document.addEventListener('mousedown', e => {
                if (menu.getAttribute('data-open') === 'true' && !menu.contains(e.target) && !menuBtn.contains(e.target)) {
                    closeThemeMenu();
                }
            });
            // 初始生成后立即同步选中态
            syncThemeUI();
        }
        // 设置弹窗下拉
        const sel = document.getElementById('themeSelect');
        if (sel) sel.addEventListener('change', () => selectTheme(sel.value));
    }
    function closeThemeMenu() {
        const menu = document.getElementById('themeMenu');
        if (menu) menu.setAttribute('data-open', 'false');
    }

    StateBridge.on('theme', s => {
        if (s && s.theme) {
            // C# 持久化后回推：应用即可，不再回写，避免循环
            applyTheme(s.theme);
        }
    });
    // 收藏状态变化 → 更新所有对应红心 + 同步缓存
    StateBridge.on('favoriteChanged', s => {
        if (!s) return;
        const fav = !!s.isFavorite;
        document.querySelectorAll(`[data-fav="${CSS.escape(s.songId)}"]`).forEach(btn => {
            btn.classList.toggle('active', fav);
            btn.textContent = fav ? '♥' : '♡';
        });
        // 同步更新缓存中的歌曲收藏状态，切回页面时保持
        for (const key of Object.keys(songsCache)) {
            const songs = songsCache[key].songs;
            for (const song of songs) {
                if (song.id === s.songId) song.isFavorite = fav;
            }
        }
    });

    // 断线重连：连接丢失→显示重连横幅；恢复→隐藏横幅并刷新当前页（详情页数据已在 DOM，不重定向）。
    StateBridge.on('connection', s => {
        if (!s) return;
        const b = document.getElementById('connBanner');
        if (b) b.style.display = s.connected ? 'none' : 'block';
        if (s.connected && !['albumDetail', 'artistDetail', 'playlistDetail', 'genreDetail'].includes(currentPage)) {
            navigate(currentPage);
        }
    });

    navigate(currentPage);

    // 无限滚动监听
    setupInfiniteScroll();

    // 后台预加载常用页面数据，切页秒开
    preloadPages();
}

async function preloadPages() {
    try {
        const results = await Promise.allSettled([
            Bridge.data('getAlbumsPage', 1),
            loadPlaylistsData(),
            Bridge.data('getArtistsPage', 1),
            Bridge.data('getSongsPage', 1),
            Bridge.data('getFavorites'),
            Bridge.data('getHistory'),
        ]);
        // 专辑第 1 页写入内存缓存，切到专辑页秒开（hasMore 也要带上：缓存命中时
        // loadAlbums 直接拿它画分页器，「下一页」是不是灰的取决于它）
        if (results[0].status === 'fulfilled' && results[0].value?.albums) {
            albumsCache[1] = { albums: results[0].value.albums, hasMore: results[0].value.hasMore };
        }
        // 歌曲第 1 页写入缓存
        if (results[3].status === 'fulfilled' && results[3].value?.songs) {
            songsCache[1] = { songs: results[3].value.songs, hasMore: results[3].value.hasMore };
        }
    } catch (e) { /* 预加载失败不影响主流程 */ }
}

document.addEventListener('DOMContentLoaded', init);

