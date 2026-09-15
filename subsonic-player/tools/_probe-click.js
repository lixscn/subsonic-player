(async () => {
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const out = { events: [] };
  // 记录 C# 推来的 playback 状态，以及每次点击
  window.addEventListener('bridgeEvent', e => {
    if (e.detail?.event === 'playback') out.events.push({ t: Math.round(performance.now()), song: e.detail.payload?.currentTitle, playing: e.detail.payload?.isPlaying });
  });

  await loadSongs(1);
  await sleep(6000);
  const rows = Array.from(document.querySelectorAll('#songsList .song-row'));
  out.rowCount = rows.length;
  if (rows.length < 3) return { error: 'not enough rows' };

  const target = rows[2];
  out.clickedTitle = (target.innerText || '').split('\n')[1] || '';
  out.clickedId = target.dataset.id;
  out.titleBefore = document.querySelector('#pbTitle')?.textContent || '';

  const t0 = Math.round(performance.now());
  out.t0 = t0;
  // 真实委托路径：点行内的标题区
  const titleEl = target.querySelector('.song-title') || target;
  titleEl.click();
  await sleep(9000);
  out.msUntilStateChange = out.events.length ? out.events[0].t - t0 : -1;
  out.titleAfter = document.querySelector('#pbTitle')?.textContent || '';
  out.stateEvents = out.events.slice(0, 6);

  // 再点同页另一首，量第二次换歌
  out.events.length = 0;
  const target2 = rows[5];
  out.clickedTitle2 = (target2.innerText || '').split('\n')[1] || '';
  const t1 = Math.round(performance.now());
  (target2.querySelector('.song-title') || target2).click();
  await sleep(9000);
  out.msSecondChange = out.events.length ? out.events[0].t - t1 : -1;
  out.titleAfter2 = document.querySelector('#pbTitle')?.textContent || '';
  return out;
})()
