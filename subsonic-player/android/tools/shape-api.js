// 把真实服务端响应整理成紧凑的「字段清单」，供 Java 客户端建模核对。
// 用 Node 是因为 PowerShell 的 ConvertFrom-Json 遇到重复键（bitrate/bitRate）会直接抛错。
const fs = require('fs');
const path = require('path');

const dir = process.argv[2] || path.join(__dirname, '..', 'build', 'probe');
const files = fs.readdirSync(dir).filter(f => f.endsWith('.json')).sort();

/** 采样一个值，长字符串截断 */
function sample(v) {
  if (v === null) return 'null';
  if (Array.isArray(v)) return `[${v.length}]`;
  if (typeof v === 'object') return '{obj}';
  const s = String(v);
  return JSON.stringify(s.length > 40 ? s.slice(0, 40) + '…' : s);
}

function shape(o, depth = 0) {
  const pad = '  '.repeat(depth);
  if (Array.isArray(o)) {
    if (!o.length) return `${pad}(empty array)`;
    return `${pad}[0]:\n${shape(o[0], depth + 1)}`;
  }
  if (o && typeof o === 'object') {
    return Object.keys(o).map(k => {
      const v = o[k];
      if (Array.isArray(v)) {
        return `${pad}${k} = [${v.length}]` + (v.length && typeof v[0] === 'object' ? `\n${shape(v[0], depth + 2)}` : ` ${sample(v[0])}`);
      }
      if (v && typeof v === 'object') return `${pad}${k} = {obj}\n${shape(v, depth + 1)}`;
      return `${pad}${k} = ${sample(v)}`;
    }).join('\n');
  }
  return `${pad}${sample(o)}`;
}

for (const f of files) {
  const raw = fs.readFileSync(path.join(dir, f), 'utf8');
  let j;
  try { j = JSON.parse(raw); } catch (e) { console.log(`\n#### ${f}\n  JSON 解析失败: ${e.message}\n  raw: ${raw.slice(0, 200)}`); continue; }
  const resp = j['subsonic-response'] || j;
  console.log(`\n#### ${f}`);
  console.log(`  status=${resp.status} version=${resp.version} type=${resp.type || ''} MusicTagVersion=${resp.MusicTagVersion || ''}`);
  if (resp.error) { console.log(`  error=${JSON.stringify(resp.error)}`); continue; }
  for (const k of Object.keys(resp)) {
    if (['status', 'version', 'type', 'MusicTagVersion'].includes(k)) continue;
    console.log(`  <${k}>`);
    console.log(shape(resp[k], 2));
  }
}
