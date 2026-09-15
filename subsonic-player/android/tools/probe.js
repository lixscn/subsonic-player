// 探测真实 Subsonic 服务端：拉取原始响应落盘 + 生成字段结构清单。
// 认证信息由环境变量传入（SP_BASE / SP_AUTH），不落盘、不打印。
// 用法：node probe.js <outDir>
const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');

const OUT = process.argv[2] || path.join(__dirname, '..', 'build', 'probe');
const BASE = (process.env.SP_BASE || '').replace(/\/+$/, '');
const AUTH = process.env.SP_AUTH || '';
if (!BASE || !AUTH) { console.error('缺少 SP_BASE / SP_AUTH 环境变量'); process.exit(2); }

fs.mkdirSync(OUT, { recursive: true });

/** 原始请求（自动跟随重定向），返回 {status, headers, body:Buffer, url} */
function get(url, { timeout = 30000, method = 'GET', redirects = 3 } = {}) {
  return new Promise((resolve, reject) => {
    const mod = url.startsWith('https') ? https : http;
    const req = mod.request(url, { method, timeout }, res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => {
        const loc = res.headers.location;
        if (loc && res.statusCode >= 300 && res.statusCode < 400 && redirects > 0) {
          const next = new URL(loc, url).toString();
          resolve(get(next, { timeout, method, redirects: redirects - 1 }));
          return;
        }
        resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks), url });
      });
    });
    req.on('timeout', () => { req.destroy(new Error('timeout')); });
    req.on('error', reject);
    req.end();
  });
}

const rest = (ep, q = '', withF = true) =>
  `${BASE}/rest/${ep}?${AUTH}${withF ? '&f=json' : ''}${q}`;

/** 采样值 */
function sample(v, max = 48) {
  if (v === null) return 'null';
  if (Array.isArray(v)) return `array[${v.length}]`;
  if (typeof v === 'object') return '{obj}';
  const s = String(v);
  return JSON.stringify(s.length > max ? s.slice(0, max) + '…' : s);
}

/** 描述一个对象/数组的结构 */
function shape(v, indent = 2) {
  const pad = ' '.repeat(indent);
  if (Array.isArray(v)) {
    if (!v.length) return `${pad}(空数组)`;
    if (typeof v[0] !== 'object' || v[0] === null) return `${pad}${sample(v[0])} … 共 ${v.length} 项`;
    return `${pad}共 ${v.length} 项，元素结构：\n${shape(v[0], indent + 2)}`;
  }
  if (v && typeof v === 'object') {
    return Object.keys(v).map(k => {
      const x = v[k];
      if (Array.isArray(x) || (x && typeof x === 'object')) return `${pad}${k}:\n${shape(x, indent + 2)}`;
      return `${pad}${k} = ${sample(x)}`;
    }).join('\n');
  }
  return `${pad}${sample(v)}`;
}

const lines = [];
const log = s => { lines.push(s); console.log(s); };

/** 拉取一个 JSON 端点 */
async function probe(file, ep, q = '', note = '') {
  const url = rest(ep, q);
  try {
    const r = await get(url);
    const text = r.body.toString('utf8');
    fs.writeFileSync(path.join(OUT, file), r.body);          // 原始字节落盘，保留真实编码
    let j = null, parseErr = null;
    try { j = JSON.parse(text); } catch (e) { parseErr = e.message; }
    const resp = j && (j['subsonic-response'] || j);
    log(`\n#### ${file}   ${ep}${q}${note ? '  (' + note + ')' : ''}`);
    log(`  http=${r.status} bytes=${r.body.length} status=${resp ? resp.status : '?'} version=${resp ? resp.version : '?'}${resp && resp.type ? ' type=' + resp.type : ''}`);
    if (parseErr) { log(`  ⚠ JSON 解析失败（重复键等）：${parseErr}`); log(`  raw head: ${text.slice(0, 240)}`); return j; }
    if (!resp) { log('  ⚠ 无 subsonic-response 节点'); return j; }
    if (resp.error) { log(`  error=${JSON.stringify(resp.error)}`); return resp; }
    for (const k of Object.keys(resp)) {
      if (['status', 'version', 'type', 'MusicTagVersion'].includes(k)) continue;
      log(`  <${k}>`);
      log(shape(resp[k], 4));
    }
    return resp;
  } catch (e) {
    log(`\n#### ${file}   ${ep}${q}\n  ✗ 请求失败：${e.message}`);
    return null;
  }
}

/** 探测二进制端点（不带 f 参数） */
async function probeBinary(file, ep, q, note) {
  const url = rest(ep, q, false);
  try {
    const r = await get(url, { timeout: 30000 });
    log(`\n#### ${file}   ${ep}  (${note})`);
    log(`  http=${r.status} content-type=${r.headers['content-type']} content-length=${r.headers['content-length']} accept-ranges=${r.headers['accept-ranges'] || '(无)'}`);
    if (r.status !== 200) log(`  body head: ${r.body.toString('utf8').slice(0, 200)}`);
  } catch (e) {
    log(`\n#### ${file}   ${ep}\n  ✗ 请求失败：${e.message}`);
  }
}

(async () => {
  log(`# Subsonic API 探测报告   base=${BASE.replace(/\/\/.*@/, '//')}`);
  log(`# 生成时间 ${new Date().toISOString()}`);

  await probe('ping.json', 'ping');
  await probe('musicfolders.json', 'getMusicFolders');
  await probe('indexes.json', 'getIndexes');
  await probe('artists.json', 'getArtists');
  await probe('albumlist-alphabetical.json', 'getAlbumList2', '&type=alphabeticalByArtist&size=4&offset=0');
  await probe('albumlist-random.json', 'getAlbumList2', '&type=random&size=3');
  await probe('albumlist-recent.json', 'getAlbumList2', '&type=recent&size=3');
  await probe('albumlist-frequent.json', 'getAlbumList2', '&type=frequent&size=3');
  await probe('albumlist-newest.json', 'getAlbumList2', '&type=newest&size=3');
  await probe('albumlist-starred.json', 'getAlbumList2', '&type=starred&size=3');
  await probe('albumlist-byYear.json', 'getAlbumList2', '&type=byYear&fromYear=2000&toYear=2026&size=3');
  await probe('albumlist-byGenre.json', 'getAlbumList2', '&type=byGenre&genre=%E6%B5%81%E8%A1%8C&size=3');

  const albums = await probe('albumlist-25.json', 'getAlbumList2', '&type=alphabeticalByArtist&size=25&offset=0');
  const albumId = albums?.albumList2?.album?.[0]?.id;
  const album = albumId ? await probe('album.json', 'getAlbum', `&id=${albumId}`) : null;
  const songId = album?.album?.song?.[0]?.id;
  const artistId = albums?.albumList2?.album?.[0]?.artistId;

  if (artistId) {
    await probe('artist.json', 'getArtist', `&id=${artistId}`);
    await probe('artistinfo2.json', 'getArtistInfo2', `&id=${artistId}`);
  }
  await probe('search3.json', 'search3', '&query=%E7%88%B1&songCount=5&albumCount=5&artistCount=5');
  await probe('starred2.json', 'getStarred2');
  await probe('genres.json', 'getGenres');
  await probe('songsbygenre.json', 'getSongsByGenre', '&genre=%E6%9C%AA%E7%9F%A5&count=5');
  await probe('playlists.json', 'getPlaylists');
  await probe('bookmarks.json', 'getBookmarks');
  await probe('playqueue.json', 'getPlayQueue');
  await probe('scanstatus.json', 'getScanStatus');
  if (songId) {
    await probe('song.json', 'getSong', `&id=${songId}`);
    await probe('lyricsbysongid.json', 'getLyricsBySongId', `&id=${songId}`);
  }
  const a = album?.album?.song?.[0];
  if (a) await probe('lyrics.json', 'getLyrics', `&artist=${encodeURIComponent(a.artist || '')}&title=${encodeURIComponent(a.title || '')}`);

  // 二进制端点（不能带 f 参数）
  const coverId = albums?.albumList2?.album?.[0]?.coverArt;
  if (coverId) await probeBinary('coverart.bin', 'getCoverArt', `&id=${encodeURIComponent(coverId)}&size=300`, '封面');
  if (songId) {
    await probeBinary('stream-raw.bin', 'stream', `&id=${songId}`, '原始流');
    await probeBinary('stream-mp3-192.bin', 'stream', `&id=${songId}&maxBitRate=192&format=mp3`, '转码 mp3 192');
    await probeBinary('stream-json.bin', 'stream', `&id=${songId}&f=json`, '带 f=json（预期失败）');
    await probeBinary('download.bin', 'download', `&id=${songId}`, '原始文件下载');
  }

  fs.writeFileSync(path.join(OUT, 'SHAPES.txt'), lines.join('\n'), 'utf8');
  console.log(`\n报告已写入 ${path.join(OUT, 'SHAPES.txt')}`);
})();
