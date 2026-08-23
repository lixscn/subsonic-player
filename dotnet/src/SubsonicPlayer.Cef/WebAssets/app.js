'use strict';

// ============ Bridge 封装 ============
// window.bridge 由 C# 注入（RegisterJavascriptObject），提供对 C# 服务的调用。

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
        return fn(method, argsJson);
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

// ============ 导航 ============
const NAV_ITEMS = [
    { key: 'discover', label: '发现', icon: '#i-compass' },
    { key: 'nowPlaying', label: '正在播放', icon: '#i-now' },
    { key: 'albums', label: '专辑', icon: '#i-album' },
    { key: 'artists', label: '艺术家', icon: '#i-artist' },
    { key: 'songs', label: '歌曲', icon: '#i-music' },
    { key: 'playlists', label: '歌单', icon: '#i-list' },
    { key: 'favorites', label: '收藏', icon: '#i-heart-nav' },
    { key: 'history', label: '历史', icon: '#i-history' },
    { key: 'bookmarks', label: '书签', icon: '#i-bookmark' },
    { key: 'search', label: '搜索', icon: '#i-search' },
];

let currentPage = 'discover';

const PAGE_IDS = {
    discover: 'pageDiscover', albums: 'pageAlbums', albumDetail: 'pageAlbumDetail',
    search: 'pageSearch', playlists: 'pagePlaylists', playlistDetail: 'pagePlaylistDetail',
    songs: 'pageSongs', artists: 'pageArtists', artistDetail: 'pageArtistDetail',
    favorites: 'pageFavorites', history: 'pageHistory', bookmarks: 'pageBookmarks',
    nowPlaying: 'pageNowPlaying', placeholder: 'pagePlaceholder',
};

function showPage(key) {
    for (const k of Object.keys(PAGE_IDS)) {
        const el = document.getElementById(PAGE_IDS[k]);
        if (el) el.style.display = k === key ? '' : 'none';
    }
    currentPage = key;
}

function initNav() {
    const list = document.getElementById('navList');
    NAV_ITEMS.forEach(item => {
        const btn = document.createElement('button');
        btn.className = 'nav-item';
        btn.dataset.key = item.key;
        btn.innerHTML = `<span class="nav-icon"><svg width="17" height="17"><use href="${item.icon}"/></svg></span><span>${item.label}</span>`;
        btn.addEventListener('click', () => navigate(item.key));
        list.appendChild(btn);
    });
}

function navigate(key) {
    document.querySelectorAll('.nav-item').forEach(b => {
        b.classList.toggle('active', b.dataset.key === key);
    });
    const pageKey = (key === 'albumDetail' || key === 'playlistDetail' || key === 'artistDetail') ? 'placeholder' : key;
    showPageWithTransition(pageKey);
    switch (key) {
        case 'discover': loadDiscover(); break;
        case 'albums': loadAlbums(1); break;
        case 'playlists': loadPlaylists(); break;
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

function albumCardHtml(a) {
    const cover = a.coverUrl || '';
    return `<div class="album-card" data-id="${esc(a.id)}" data-name="${esc(a.name)}">
        <div class="album-cover-wrap">
            ${cover ? `<img src="${cover}" alt="" onerror="this.style.display='none';this.nextElementSibling.style.display='flex'">` : ''}
            <div class="cover-fallback"><svg class="ic" width="34" height="34"><use href="#i-album"/></svg></div>
            <div class="play-overlay"><button class="play-btn" data-play-album="${esc(a.id)}"><svg class="ic" width="18" height="18"><use href="#i-now"/></svg></button></div>
        </div>
        <div class="album-name">${esc(a.name)}</div>
        <div class="album-artist">${esc(a.artist || '')}</div>
    </div>`;
}

function songRowHtml(s) {
    const cover = s.coverUrl || '';
    const songJson = encodeURIComponent(JSON.stringify(s));
    return `<div class="song-row" data-id="${esc(s.id)}" data-idx="${s.index ?? ''}" data-song="${songJson}">
        <span class="song-index">${s.index ?? ''}</span>
        ${cover
            ? `<span class="song-cover-wrap"><img class="song-cover" src="${cover}" alt="" onerror="this.style.display='none';this.parentElement.classList.add('no-cover')"><svg class="cover-fb" width="18" height="18"><use href="#i-music"/></svg></span>`
            : `<span class="song-cover-wrap no-cover"><svg class="cover-fb" width="18" height="18"><use href="#i-music"/></svg></span>`}
        <span class="song-title">${esc(s.title)}</span>
        <span class="song-artist">${esc(s.artist || '')}</span>
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
        const idxEl = row.querySelector('.song-index');
        if (idxEl) idxEl.innerHTML = isCurrent
            ? '<svg width="14" height="14" class="ic eq-icon"><use href="#i-now"/></svg>'
            : row.dataset.idx || '';
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
            : await Bridge.data('getDiscoverMore');
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

/// 异步加载专辑区块（最新/常听/高分），先占位后填充；同时带回智能推荐供 tab 复用
async function loadDiscoverMore() {
    const more = document.getElementById('discoverMore');
    try {
        const d = await Bridge.data('getDiscoverMore');
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
        const body = document.getElementById('discoverTabBody');
        body.innerHTML = skelRowsHtml(5);
        loadDiscoverTab();
    });
}

// ============ 专辑列表（流式加载 + 首屏缓存） ============
let albumsPage = 1;
let albumsLoading = false;
let albumsDone = false;
const albumsCache = {}; // page -> { albums }

async function loadAlbums(page) {
    albumsPage = page;
    showPageWithTransition('albums');
    const grid = document.getElementById('albumsGrid');
    const loading = document.getElementById('albumsLoading');

    // 有缓存首屏 → 直接渲染秒开
    if (albumsCache[1]) {
        grid.innerHTML = albumsCache[1].albums.map(albumCardHtml).join('');
        albumsPage = 1;
        albumsDone = albumsCache[1].albums.length < 20;
        loading.style.display = 'none';
        return;
    }

    grid.innerHTML = skelGridHtml();
    albumsDone = false;
    albumsLoading = false;
    loading.style.display = 'none';
    await appendAlbums(1);
}

async function appendAlbums(page) {
    const grid = document.getElementById('albumsGrid');
    const loading = document.getElementById('albumsLoading');
    if (albumsLoading || albumsDone) return;

    // 缓存命中直接追加
    if (albumsCache[page]) {
        const cards = albumsCache[page].albums.map(albumCardHtml).join('');
        if (grid.querySelector('.skel-rows, .skel-albums, .page-loading')) grid.innerHTML = cards;
        else grid.insertAdjacentHTML('beforeend', cards);
        albumsPage = page;
        if (albumsCache[page].albums.length < 20) albumsDone = true;
        return;
    }

    albumsLoading = true;
    loading.style.display = '';
    try {
        const d = await Bridge.data('getAlbumsPage', page);
        loading.style.display = 'none';
        if (d.status) {
            if (!grid.children.length) grid.innerHTML = `<div class="status-line">${esc(d.status)}</div>`;
            albumsDone = true;
            return;
        }
        if (!d.albums.length) { albumsDone = true; return; }
        albumsCache[page] = { albums: d.albums };
        const cards = d.albums.map(albumCardHtml).join('');
        // 首次（容器为骨架）替换；流式追加
        if (grid.querySelector('.skel-rows, .skel-albums, .page-loading')) grid.innerHTML = cards;
        else grid.insertAdjacentHTML('beforeend', cards);
        albumsPage = page;
        // 若本页不足一页说明已到末尾
        if (d.albums.length < 20) albumsDone = true;
    } catch (err) {
        loading.style.display = 'none';
        if (!grid.children.length) grid.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
    } finally {
        albumsLoading = false;
    }
}

// 内容区滚动到底部 → 自动加载更多（流式）
function setupInfiniteScroll() {
    const content = document.getElementById('content');
    content.addEventListener('scroll', () => {
        const nearBottom = content.scrollTop + content.clientHeight >= content.scrollHeight - 200;
        if (!nearBottom) return;
        if (currentPage === 'albums') appendAlbums(albumsPage + 1);
        else if (currentPage === 'songs') appendSongs(songsPage + 1);
        else if (currentPage === 'artists') appendArtists();
    });
}

// ============ 专辑详情 ============
async function openAlbumDetail(id) {
    showPage('albumDetail');
    const body = document.getElementById('albumDetailBody');
    body.innerHTML = skelDetailHtml();
    try {
        const a = await Bridge.data('getAlbumDetail', id);
        if (!a) { body.innerHTML = '<div class="status-line">专辑不存在</div>'; return; }
        body.innerHTML = `<div class="album-detail-header">
            ${a.coverUrl ? `<img class="album-detail-cover" src="${a.coverUrl}" alt="">` : `<div class="album-detail-cover"></div>`}
            <div class="album-detail-meta">
                <h1>${esc(a.name)}</h1>
                <div class="sub">${esc(a.artist || '')} · ${a.songCount || 0} 首 · ${esc(a.year || '')}</div>
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

// ============ 歌曲（流式加载） ============
let songsPage = 1;
let songsLoading = false;
let songsDone = false;
const songsCache = {};

async function loadSongs(page) {
    songsPage = 1;
    songsDone = false;
    songsLoading = false;
    showPage('songs');
    const list = document.getElementById('songsList');

    // 首屏缓存秒开
    if (songsCache[1]) {
        list.innerHTML = songsCache[1].songs.map(songRowHtml).join('');
        songsPage = 1;
        songsDone = songsCache[1].songs.length < 20;
        markCurrentSong();
        return;
    }

    list.innerHTML = skelSongListHtml();
    await appendSongs(1);
}

async function appendSongs(page) {
    const list = document.getElementById('songsList');
    if (songsLoading || songsDone) return;

    if (songsCache[page]) {
        const rows = songsCache[page].songs.map(songRowHtml).join('');
        if (list.querySelector('.skel-rows, .page-loading')) list.innerHTML = rows;
        else list.insertAdjacentHTML('beforeend', rows);
        songsPage = page;
        if (songsCache[page].songs.length < 20) songsDone = true;
        markCurrentSong();
        return;
    }

    songsLoading = true;
    try {
        // 传 startIndex 保证序号跨页连续
        const existing = list.querySelectorAll('.song-row').length;
        const d = await Bridge.data('getSongsPage', page, 20, existing);
        if (d.status && !list.children.length) { list.innerHTML = `<div class="status-line">${esc(d.status)}</div>`; songsDone = true; return; }
        const rows = (d.songs || []).map(songRowHtml).join('');
        if (!rows.length) { songsDone = true; return; }
        songsCache[page] = { songs: d.songs };
        // 首次（容器为骨架）替换；流式追加
        if (list.querySelector('.skel-rows, .page-loading')) list.innerHTML = rows;
        else list.insertAdjacentHTML('beforeend', rows);
        songsPage = page;
        if ((d.songs || []).length < 20) songsDone = true;
        markCurrentSong();
    } catch (err) {
        if (!list.children.length) list.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
    } finally {
        songsLoading = false;
    }
}

// ============ 艺术家 ============
let artistsPage = 1;
async function loadArtists(page) {
    artistsPage = page;
    artistsOffset = (page - 1) * 100;
    showPage('artists');
    const grid = document.getElementById('artistsGrid');
    grid.innerHTML = skelGridHtml(8);
    try {
        const d = await Bridge.data('getArtistsPage', page);
        renderArtistGroups(d.groups || []);
        grid.innerHTML = (d.artists || []).map(artistCardHtml).join('') || '<div class="status-line">暂无艺术家</div>';
        initArtistLazyLoad();
    } catch (err) {
        grid.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
    }
}

let artistsOffset = 0;
let artistsDone = false;
let artistsLoading = false;

/// 渲染艺术家卡片 HTML（头像惰性加载代表专辑封面）
function artistCardHtml(a) {
    return `
        <div class="artist-card" data-artist-id="${esc(a.id)}">
            <div class="artist-avatar lazy-avatar" data-lazy-artist="${esc(a.id)}" style="background:${artistColor(a.name)}">${esc(artistInitial(a.name))}</div>
            <div class="artist-name">${esc(a.name)}</div>
            <div class="artist-albums">${a.albumCount || 0} 张专辑</div>
        </div>`;
}

/// 艺术家头像惰性加载：滚动到可见时取代表专辑封面
let _artistObserver = null;
function initArtistLazyLoad() {
    if (!('IntersectionObserver' in window)) return;
    if (_artistObserver) _artistObserver.disconnect();
    _artistObserver = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (!entry.isIntersecting) return;
            const el = entry.target;
            const artistId = el.dataset.lazyArtist;
            if (!artistId || el.dataset.loaded) return;
            el.dataset.loaded = '1';
            _artistObserver.unobserve(el);
            Bridge.data('getArtistCover', artistId).then(d => {
                if (d?.coverUrl && el.isConnected) {
                    el.style.backgroundImage = `url("${d.coverUrl}")`;
                    el.style.backgroundSize = 'cover';
                    el.style.backgroundPosition = 'center';
                    el.textContent = '';
                    el.classList.add('has-image');
                }
            }).catch(() => {});
        });
    }, { rootMargin: '200px' });
    document.querySelectorAll('.lazy-avatar').forEach(el => _artistObserver.observe(el));
}

/// 渲染右侧 A-Z 字母导航条
function renderArtistGroups(groups) {
    const bar = document.getElementById('artistAlpha');
    if (!bar) return;
    bar.innerHTML = groups.map(g => `
        <button class="alpha-btn" data-offset="${g.offset}" title="${esc(g.label)}">${esc(g.label || '?')}</button>`).join('');
    bar.querySelectorAll('.alpha-btn').forEach(btn => {
        btn.addEventListener('click', async () => {
            const off = parseInt(btn.dataset.offset);
            artistsOffset = off;
            artistsDone = false;
            const grid = document.getElementById('artistsGrid');
            grid.innerHTML = skelGridHtml(8);
            try {
                const d = await Bridge.data('getArtistsAt', off);
                grid.innerHTML = (d.artists || []).map(artistCardHtml).join('') || '<div class="status-line">暂无艺术家</div>';
                document.getElementById('content').scrollTop = 0;
                initArtistLazyLoad();
            } catch (err) {
                grid.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
            }
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

/// 艺术家流式加载（基于 offset）
async function appendArtists() {
    const grid = document.getElementById('artistsGrid');
    if (artistsLoading || artistsDone) return;
    artistsLoading = true;
    try {
        const d = await Bridge.data('getArtistsAt', artistsOffset + grid.querySelectorAll('.artist-card').length);
        if (!(d.artists || []).length) { artistsDone = true; return; }
        grid.insertAdjacentHTML('beforeend', d.artists.map(artistCardHtml).join(''));
        initArtistLazyLoad();
        if (d.artists.length < 100) artistsDone = true;
    } catch (e) {
        artistsDone = true;
    } finally {
        artistsLoading = false;
    }
}

async function openArtistDetail(id) {
    showPage('artistDetail');
    const body = document.getElementById('artistDetailBody');
    body.innerHTML = skelGridHtml(8);
    try {
        const a = await Bridge.data('getArtistDetail', id);
        if (!a) { body.innerHTML = '<div class="status-line">艺术家不存在</div>'; return; }
        body.innerHTML = `<div class="album-detail-meta" style="margin-bottom:20px">
            <h1>${esc(a.name)}</h1>
            <div class="sub">${a.albumCount || 0} 张专辑</div>
        </div>
        <div class="album-grid">${(a.albums || []).map(albumCardHtml).join('') || '<div class="status-line">暂无专辑</div>'}</div>`;
    } catch (err) {
        body.innerHTML = `<div class="status-line">加载失败: ${esc(err.message)}</div>`;
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
    npTitle: document.getElementById('npTitle'),
    npArtist: document.getElementById('npArtist'),
    npCover: document.getElementById('npCover'),};

function updatePlayerBar(s) {
    if (!s) return;
    latestPlayback = s;
    playerBar.title.textContent = s.currentTitle || '未在播放';
    playerBar.artist.textContent = s.currentArtist || '';
    const pct = s.durationSeconds > 0 ? (s.positionSeconds / s.durationSeconds) * 100 : 0;
    playerBar.pos.textContent = fmtTime(s.positionSeconds);
    playerBar.dur.textContent = fmtTime(s.durationSeconds);
    playerBar.fill.style.width = pct + '%';
    playerBar.thumb.style.left = pct + '%';
    // 播放/暂停图标切换（SVG use，无闪烁）
    const playUse = document.querySelector('#btnPlayIcon use');
    if (playUse) playUse.setAttribute('href', s.isPlaying ? '#i-pause' : '#i-play');
    // 红心切换
    playerBar.fav.classList.toggle('active', !!s.isFavorite);
    const favUse = document.querySelector('#pbFavIcon use');
    if (favUse) favUse.setAttribute('href', s.isFavorite ? '#i-heart' : '#i-heart-outline');
    if (typeof s.volume === 'number') playerBar.volume.value = s.volume;
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

// ============ 事件绑定 ============
function initEvents() {
    playerBar.playBtn.addEventListener('click', () => Bridge.invoke('togglePlay'));
    document.getElementById('btnPrev').addEventListener('click', () => Bridge.invoke('previous'));
    document.getElementById('btnNext').addEventListener('click', () => Bridge.invoke('next'));
    playerBar.fav.addEventListener('click', () => Bridge.invoke('toggleFavorite'));

    playerBar.volume.addEventListener('input', () => {
        Bridge.invoke('setVolume', parseFloat(playerBar.volume.value));
    });
    playerBar.modeBtn.addEventListener('click', () => Bridge.invoke('togglePlayMode'));

    const track = document.getElementById('pbTrack');
    track.addEventListener('click', e => {
        const rect = track.getBoundingClientRect();
        const ratio = (e.clientX - rect.left) / rect.width;
        Bridge.invoke('seek', ratio);
    });

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
    document.getElementById('btnMini').addEventListener('click', () => navigate('nowPlaying'));

document.getElementById('topThemeBtn').addEventListener('click', () => Bridge.invoke('toggleTheme'));
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

    const select = document.getElementById('serviceSelect');
    select.addEventListener('change', () => Bridge.invoke('switchService', select.value));

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
    document.getElementById('artistBack').addEventListener('click', () => navigate('artists'));

    // 全局委托：专辑卡 / 歌曲行 / 播放列表 / 收藏按钮
    document.getElementById('content').addEventListener('click', async e => {
        const albumCard = e.target.closest('.album-card');
        if (albumCard && albumCard.dataset.id) {
            openAlbumDetail(albumCard.dataset.id);
            return;
        }
        const playlistCard = e.target.closest('.album-card[data-playlist-id]');
        if (playlistCard) { openPlaylistDetail(playlistCard.dataset.playlistId); return; }
        const artistCard = e.target.closest('.artist-card');
        if (artistCard) { openArtistDetail(artistCard.dataset.artistId); return; }

        const playBtn = e.target.closest('[data-play-album]');
        if (playBtn) { playOnce('playAlbum:' + playBtn.dataset.playAlbum, () => Bridge.invoke('playAlbum', playBtn.dataset.playAlbum)); return; }

        const playList = e.target.closest('[data-play-list]');
        if (playList) {
            const listEl = playList.closest('.section').querySelector('.song-list');
            const songs = collectSongs(listEl);
            if (songs.length) playOnce('playList:' + songs.map(s => s.id).join(','), () => Bridge.invoke('playSongsJson', JSON.stringify(songs), 0));
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
                playOnce('bookmark:' + songRow.dataset.songId, () => Bridge.invoke('playBookmark', songRow.dataset.songId || '', 0));
                return;
            }
            const listEl = songRow.closest('.song-list');
            const songs = collectSongs(listEl);
            const start = songs.findIndex(s => s.id === songRow.dataset.id);
            if (songs.length) playOnce('song:' + songRow.dataset.id, () => Bridge.invoke('playSongsJson', JSON.stringify(songs), Math.max(0, start)));
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
function renderServices(services) {
    const select = document.getElementById('serviceSelect');
    select.innerHTML = '';
    (services || []).forEach(s => {
        const opt = document.createElement('option');
        opt.value = s.id;
        opt.textContent = s.name;
        select.appendChild(opt);
    });
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
            await renderServices((await Bridge.invoke('getServices')).services);
        });
    });
}

function _clearSvcForm() {
    document.getElementById('svcName').value = '';
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
    document.getElementById('svcSave').addEventListener('click', async () => {
        const id = _editingSvcId || 'svc_' + Date.now();
        await Bridge.invoke('saveService', id,
            document.getElementById('svcName').value,
            document.getElementById('svcLan').value,
            document.getElementById('svcWan').value,
            document.getElementById('svcUser').value,
            document.getElementById('svcPass').value);
        _editingSvcId = null;
        await renderServiceList();
        _clearSvcForm();
        const data = await Bridge.invoke('getServices');
        renderServices(data?.services);
        if (data?.currentServiceId) document.getElementById('serviceSelect').value = data.currentServiceId;
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

async function openSidePanel() {
    const panel = document.getElementById('queuePanel');
    sidePanelOpen = true;
    panel.style.right = '0px';
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
    panel.style.right = '0px';
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
                <div class="qi-artist">${esc(s.artist || '')}</div>
            </div>
            <span class="qi-dur">${esc(s.durationText || '')}</span>
        </div>`).join('');
    body.querySelectorAll('.queue-item').forEach(item => {
        item.addEventListener('click', () => {
            const qidx = parseInt(item.dataset.qidx);
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
    } else {
        body.innerHTML = `<div class="lyrics-view"><div class="lyric-plain">${esc(data.text || '暂无歌词')}</div></div>`;
    }
}

// ============ 初始化 ============
async function init() {
    initNav();
    initEvents();
    initSettingsModalEvents();
    initDiscoverEvents();

    try {
        const initial = await Bridge.invoke('getInitialState');
        if (initial) {
            updatePlayerBar(initial.playback);
            renderServices(initial.services);
            const select = document.getElementById('serviceSelect');
            if (initial.currentServiceId) select.value = initial.currentServiceId;
        }
    } catch (err) {
        console.error('获取初始状态失败:', err);
    }

    StateBridge.on('playback', s => updatePlayerBar(s));
    StateBridge.on('services', s => {
        renderServices(s.services);
        const select = document.getElementById('serviceSelect');
        if (s.currentServiceId) select.value = s.currentServiceId;
        // 服务配置变更后：清空数据缓存并重新加载当前页面，确保新配置的数据立即生效
        for (const k of Object.keys(pageCache)) delete pageCache[k];
        for (const k of Object.keys(albumsCache)) delete albumsCache[k];
        for (const k of Object.keys(songsCache)) delete songsCache[k];
        if (typeof currentPage === 'string' && currentPage) navigate(currentPage);
    });
    StateBridge.on('theme', s => {
        if (s && s.theme) {
            const isLight = s.theme === 'light';
            document.documentElement.classList.toggle('light', isLight);
            // 更新主题图标
            const iconUse = document.querySelector('#topThemeIcon use');
            if (iconUse) iconUse.setAttribute('href', isLight ? '#i-sun' : '#i-moon');
            // 侧栏按钮文字
            const themeBtn = document.getElementById('themeBtn');
            if (themeBtn) themeBtn.textContent = isLight ? '☀ 浅色' : '🌙 深色';
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
        // 专辑第 1 页写入内存缓存，切到专辑页秒开
        if (results[0].status === 'fulfilled' && results[0].value?.albums) {
            albumsCache[1] = { albums: results[0].value.albums };
        }
        // 歌曲第 1 页写入缓存
        if (results[3].status === 'fulfilled' && results[3].value?.songs) {
            songsCache[1] = { songs: results[3].value.songs };
        }
    } catch (e) { /* 预加载失败不影响主流程 */ }
}

document.addEventListener('DOMContentLoaded', init);
