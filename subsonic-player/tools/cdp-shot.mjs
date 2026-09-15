// 截图工具：连 CEF 的 CDP 截当前页面，存成 PNG（调主题/底图这类视觉改动时用来对比）。
// 前置：临时在 Program.cs 打开 --remote-debugging-port=9333（见 AGENTS.md），跑完删掉。
// 用法: node tools/cdp-shot.mjs out.png ["要执行的 JS"] [等多少毫秒]
// 例： node tools/cdp-shot.mjs shots/a.png "await loadAlbums(1)" 5000
//      node tools/cdp-shot.mjs shots/blur8.png "document.getElementById('bgImg').style.filter='blur(8px)'" 1500
const list = await (await fetch('http://127.0.0.1:9333/json')).json();
const page = list.find(t => t.type === 'page');
if (!page) { console.error('no page target'); process.exit(1); }

const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const pending = new Map();
ws.addEventListener('message', ev => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
});
const send = (method, params = {}) => new Promise(r => { const mid = ++id; pending.set(mid, r); ws.send(JSON.stringify({ id: mid, method, params })); });
await new Promise(r => ws.addEventListener('open', r, { once: true }));
await send('Runtime.enable');
await send('Page.enable');

const out = process.argv[2] || 'shot.png';
if (process.argv[3]) {
  await send('Runtime.evaluate', { expression: process.argv[3], awaitPromise: true, returnByValue: true });
  await new Promise(r => setTimeout(r, Number(process.argv[4] || 3000)));
}

// 可选第 5 个参数：裁剪区域 "x,y,w,h[,scale]"（要看清小字时用，scale 2 = 放大两倍）
const shotParams = { format: 'png', captureBeyondViewport: false };
if (process.argv[5]) {
  const [x, y, width, height, scale] = process.argv[5].split(',').map(Number);
  shotParams.clip = { x, y, width, height, scale: scale || 1 };
}
const r = await send('Page.captureScreenshot', shotParams);
if (r.error || !r.result?.data) {
  console.error('captureScreenshot failed:', JSON.stringify(r.error || r));
  ws.close();
  process.exit(2);
}
const fs = await import('node:fs/promises');
await fs.writeFile(out, Buffer.from(r.result.data, 'base64'));
console.log('saved', out, Buffer.from(r.result.data, 'base64').length, 'bytes');
ws.close();
