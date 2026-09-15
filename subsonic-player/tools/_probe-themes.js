(async () => {
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const out = {};
  for (const t of ['dark', 'forest', 'midnight', 'sunset', 'rose', 'light']) {
    document.documentElement.dataset.theme = t;
    await sleep(400);
    const g = s => getComputedStyle(document.querySelector(s)).backgroundColor;
    out[t] = {
      accent: getComputedStyle(document.documentElement).getPropertyValue('--accent').trim(),
      panel: g('.sidebar'),
      main: g('.main-area'),
      list: g('.song-list'),
      card: g('.search-box input'),
      border: getComputedStyle(document.documentElement).getPropertyValue('--border').trim(),
      text2: getComputedStyle(document.documentElement).getPropertyValue('--text-secondary').trim(),
    };
  }
  document.documentElement.dataset.theme = 'dark';
  return out;
})()
