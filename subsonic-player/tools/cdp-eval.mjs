// 在 CEF 页面里执行一段 JS 并打印结果（排查用）。
// 前置：同 cdp-check-pager.mjs，需临时打开 --remote-debugging-port=9333（见 AGENTS.md），跑完删掉。
// 用法: node tools/cdp-eval.mjs "<js 表达式>"   或   node tools/cdp-eval.mjs path/to/probe.js
const list = await (await fetch('http://127.0.0.1:9333/json')).json();
const page = list.find(t => t.type === 'page');
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
const arg = process.argv[2];
const expr = arg.endsWith('.js') ? await (await import('node:fs/promises')).readFile(arg, 'utf8') : arg;
const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
console.log(JSON.stringify(r, null, 2));
ws.close();
