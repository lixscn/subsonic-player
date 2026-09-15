// 通过 CDP 在页面里实测 .song-row 的真实计算样式（临时排查用）
// 用法：应用需以 --remote-debugging-port=9333 启动
const res = await fetch('http://127.0.0.1:9333/json');
const targets = await res.json();
const page = targets.find(t => t.type === 'page');
if (!page) { console.log('没有 page target:', JSON.stringify(targets.map(t => t.type))); process.exit(1); }

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
let seq = 0;
function send(method, params) {
    return new Promise(resolve => {
        const id = ++seq;
        const onMsg = e => {
            const m = JSON.parse(e.data);
            if (m.id === id) { ws.removeEventListener('message', onMsg); resolve(m); }
        };
        ws.addEventListener('message', onMsg);
        ws.send(JSON.stringify({ id, method, params }));
    });
}

const expr = `(() => {
  const d = document.createElement('div');
  d.className = 'song-row';
  d.innerHTML = '<span class="song-index">1</span>'
    + '<span class="song-cover-wrap"></span>'
    + '<span class="song-title">标题</span>'
    + '<span class="song-artist">歌手</span>'
    + '<span class="song-album">专辑</span>'
    + '<span class="song-duration">3:00</span>'
    + '<button class="song-fav">x</button>';
  const list = document.querySelector('.song-list') || document.body;
  list.appendChild(d);
  const cs = getComputedStyle(d);
  const r = d.getBoundingClientRect();
  const lr = list.getBoundingClientRect();
  const out = {
    innerWidth: window.innerWidth,
    innerHeight: window.innerHeight,
    gridTemplateColumns: cs.gridTemplateColumns,
    gap: cs.gap,
    padding: cs.padding,
    rowWidth: Math.round(r.width),
    listWidth: Math.round(lr.width),
    sheets: [...document.styleSheets].map(s => s.href || '(inline)'),
  };
  d.remove();
  return JSON.stringify(out, null, 1);
})()`;

const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true });
console.log(r.result?.result?.value ?? JSON.stringify(r));
ws.close();
