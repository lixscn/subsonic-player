// 分页器 + 歌手页链接的端到端检查：连 CEF 的 CDP，在真 UI 里点分页器/点 A-Z，
// 断言「整页替换 + 高亮页码一致 + 歌手页歌曲行没有歌手名链接」。
//
// 前置：临时在 Program.cs 的 CefRuntimeLoader.Initialize 第二参数里加
//   new[] { new KeyValuePair<string,string>("remote-debugging-port", "9333") }
// 跑完必须删掉这个端口（见 AGENTS.md「调试」）。
// 用法：node tools/cdp-check-pager.mjs
const list = await (await fetch('http://127.0.0.1:9333/json')).json();
const page = list.find(t => t.type === 'page');
if (!page) { console.error('no page target'); process.exit(1); }

const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const pending = new Map();
const logs = [];

ws.addEventListener('message', ev => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
  } else if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
    logs.push('console.error: ' + msg.params.args.map(a => a.value ?? a.description ?? a.type).join(' '));
  } else if (msg.method === 'Runtime.exceptionThrown') {
    logs.push('exception: ' + (msg.params.exceptionDetails?.exception?.description || msg.params.exceptionDetails?.text));
  }
});

const send = (method, params = {}) => new Promise((resolve, reject) => {
  const mid = ++id;
  pending.set(mid, { resolve, reject });
  ws.send(JSON.stringify({ id: mid, method, params }));
});

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function evaluate(expr) {
  const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
  return r.result.value;
}

await new Promise((resolve, reject) => {
  ws.addEventListener('open', resolve, { once: true });
  ws.addEventListener('error', reject, { once: true });
});
await send('Runtime.enable');

const out = {};
const txt = sel => `document.querySelector('${sel}')?.innerText.replace(/\\s+/g,' ') || '(none)'`;

// 1) 专辑页
await evaluate(`(async () => { await loadAlbums(1); return 1; })()`);
await sleep(3000);
out.albumsPager1 = await evaluate(txt('[data-pager-host="albums"]'));
out.albumsCards1 = await evaluate(`document.querySelectorAll('#albumsGrid .album-card').length`);

// 2) 真点"下一页"（走全局委托的 click 路径，不是直接调函数）
out.albumsClickNext = await evaluate(`(() => {
  const b = document.querySelector('[data-pager-host="albums"] [data-pager][data-page="2"]');
  if (!b) return 'no-btn';
  b.click(); return 'clicked';
})()`);
await sleep(3500);
out.albumsPagerAfterNext = await evaluate(txt('[data-pager-host="albums"]'));
out.albumsActivePage = await evaluate(`document.querySelector('[data-pager-host="albums"] .pager-btn.active')?.textContent`);
out.albumsCards2 = await evaluate(`document.querySelectorAll('#albumsGrid .album-card').length`);

// 3) 点"首页"回到第 1 页
out.albumsClickHome = await evaluate(`(() => {
  const b = document.querySelector('[data-pager-host="albums"] [data-pager][data-page="1"]');
  if (!b) return 'no-btn';
  b.click(); return 'clicked';
})()`);
await sleep(3500);
out.albumsActivePageAfterHome = await evaluate(`document.querySelector('[data-pager-host="albums"] .pager-btn.active')?.textContent`);
out.albumsCardsAfterHome = await evaluate(`document.querySelectorAll('#albumsGrid .album-card').length`);

// 4) 歌曲页：每页 10 首（C# 把「按专辑展开」的扁平列表按歌切片，不是按专辑翻）
await evaluate(`(async () => { await loadSongs(1); return 1; })()`);
await sleep(6000);
out.songsPager1 = await evaluate(txt('[data-pager-host="songs"]'));
out.songsRows1 = await evaluate(`document.querySelectorAll('#songsList .song-row').length`);
out.songsArtistLinks1 = await evaluate(`document.querySelectorAll('#songsList .song-row [data-artist-id]').length`);
out.songsRowIndexFirst = await evaluate(`document.querySelector('#songsList .song-row')?.innerText.split('\\n')[0] || ''`);
out.songsRowIndexLast = await evaluate(`Array.from(document.querySelectorAll('#songsList .song-row')).pop()?.innerText.split('\\n')[0] || ''`);

// 歌曲页翻到第 2 页：还是 10 首，序号从 11 开始
out.songsClickNext = await evaluate(`(() => {
  const b = document.querySelector('[data-pager-host="songs"] [data-pager][data-page="2"]');
  if (!b) return 'no-btn';
  b.click(); return 'clicked';
})()`);
await sleep(6000);
out.songsRows2 = await evaluate(`document.querySelectorAll('#songsList .song-row').length`);
out.songsRowIndexFirst2 = await evaluate(`document.querySelector('#songsList .song-row')?.innerText.split('\\n')[0] || ''`);
out.songsActivePage2 = await evaluate(`document.querySelector('[data-pager-host="songs"] .pager-btn.active')?.textContent`);

// 5) 艺术家页 + A-Z 跳转
await evaluate(`(async () => { await loadArtists(1); return 1; })()`);
await sleep(3000);
out.artistsPager1 = await evaluate(txt('[data-pager-host="artists"]'));
out.artistsActive1 = await evaluate(`document.querySelector('[data-pager-host="artists"] .pager-btn.active')?.textContent`);
out.artistsCards1 = await evaluate(`document.querySelectorAll('#artistsGrid .artist-card').length`);
out.artistsFirstName1 = await evaluate(`document.querySelector('#artistsGrid .artist-card .artist-name')?.textContent || ''`);
out.alphaLabels = await evaluate(`Array.from(document.querySelectorAll('#artistAlpha .alpha-btn')).map(b => b.dataset.offset + ':' + b.textContent).join(' ')`);

// 点最后一个字母：应换算成页号并整页替换
out.alphaClicked = await evaluate(`(() => {
  const bs = document.querySelectorAll('#artistAlpha .alpha-btn');
  const last = bs[bs.length - 1];
  const info = { label: last.textContent, offset: last.dataset.offset };
  last.click();
  return info;
})()`);
await sleep(3200);
out.artistsPagerAfterAlpha = await evaluate(txt('[data-pager-host="artists"]'));
out.artistsActiveAfterAlpha = await evaluate(`document.querySelector('[data-pager-host="artists"] .pager-btn.active')?.textContent`);
out.artistsExpectedPage = String(Math.floor(Number(out.alphaClicked.offset) / 100) + 1);
out.artistsFirstAfterAlpha = await evaluate(`document.querySelector('#artistsGrid .artist-card .artist-name')?.textContent || ''`);
out.artistsCardsAfterAlpha = await evaluate(`document.querySelectorAll('#artistsGrid .artist-card').length`);
out.contentScrollTop = await evaluate(`document.querySelector('#content').scrollTop`);

// 6) 歌手页内部的歌曲行不应有歌手名链接
out.openArtist = await evaluate(`(async () => {
  const c = document.querySelector('#artistsGrid .artist-card');
  if (!c) return 'no-card';
  await openArtistDetail(c.dataset.artistId, c.querySelector('.artist-name')?.textContent || '');
  return c.querySelector('.artist-name')?.textContent || '';
})()`);
await sleep(3500);
out.artistSongsPager = await evaluate(txt('[data-pager-host="artistSongs"]'));
out.artistSongsRows = await evaluate(`document.querySelectorAll('#artistSongsList .song-row').length`);
out.artistSongsArtistLinks = await evaluate(`document.querySelectorAll('#artistSongsList .song-row [data-artist-id]').length`);
out.artistDetailHeaderArtistLinks = await evaluate(`document.querySelectorAll('#artistDetailBody .album-detail-meta [data-artist-id]').length`);
out.artistSongsFirstIndex = await evaluate(`document.querySelector('#artistSongsList .song-row')?.innerText.split('\\n')[0] || ''`);
// 歌手页翻到第 2 页：序号应从 11 开始（页大小 10）
out.artistSongsClickNext = await evaluate(`(() => {
  const b = document.querySelector('[data-pager-host="artistSongs"] [data-pager][data-page="2"]');
  if (!b) return 'no-btn';
  b.click(); return 'clicked';
})()`);
await sleep(6000);
out.artistSongsRows2 = await evaluate(`document.querySelectorAll('#artistSongsList .song-row').length`);
out.artistSongsFirstIndex2 = await evaluate(`document.querySelector('#artistSongsList .song-row')?.innerText.split('\\n')[0] || ''`);
out.artistSongsActive2 = await evaluate(`document.querySelector('[data-pager-host="artistSongs"] .pager-btn.active')?.textContent`);

// 7) 风格页
out.genreOpened = await evaluate(`(async () => {
  await loadGenres();
  const c = document.querySelector('#genresGrid .genre-card');
  if (!c) return 'no-genre-card';
  await openGenreDetail(c.dataset.genre);
  return c.dataset.genre;
})()`);
await sleep(3500);
out.genrePagerHtml = await evaluate(txt('[data-pager-host="genreSongs"]'));
out.genreRows = await evaluate(`document.querySelectorAll('#genreDetailBody .song-row').length`);
out.genreFirstIndex = await evaluate(`document.querySelector('#genreDetailBody .song-row')?.innerText.split('\\n')[0] || ''`);

out.jsErrors = logs;
console.log(JSON.stringify(out, null, 2));
ws.close();
