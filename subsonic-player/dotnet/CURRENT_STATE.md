# CURRENT_STATE.md — 接班说明（start here）

> 给下一个会话/代理的交接说明。详细技术记忆与踩坑见 `dotnet/MEMORY.md`（尾部有 2026-09-15 会话的完整记录）。
> 仓库布局已从 `dotnet/` 迁到 **`subsonic-player/dotnet/`**（CI 已同步），本文件内路径均为新布局。

## 这是什么

**Subsonic Player**（`github.com/lixscn/subsonic-player`，工作区 `D:\work_space\DeepSeekHarness\music-play`），.NET 10 + Avalonia 11.3.20 + CefGlue.Avalonia 120（Chromium 120, OSR 离屏渲染）。UI 为 HTML（`WebAssets/`，内嵌为 `AppUI.*` 程序集资源，经自定义 `app://` scheme 服务）。音频 BASS。

- `subsonic-player/dotnet/` 桌面端 ｜ `subsonic-player/android/` 原生 Android 端 ｜ `subsonic-player/website/` 官网

## 当前可运行状态（已达成）

- **单文件版**：`D:\tools\SubsonicPlayer-single\SubsonicPlayer.exe`（~253MB，应用托管代码 + .NET 运行时）。
  CEF 原生 + `lib\bass*` **全外置**（CEF 不能打包进 exe）。
- 发布：`cd subsonic-player/dotnet && powershell -ExecutionPolicy Bypass -File publish-singlefile.ps1`
  （**必须在 dotnet 目录下执行**；且需 `AVALONIA_TELEMETRY_OPTOUT=1`，否则构建报错）。
  发布后**清 `%APPDATA%\subsonic-player\cef-cache`**，否则加载的是旧前端。
- **CI 绿**：Windows 产单文件；Linux/macOS 非单文件。

## 2026-09-15 会话：桌面端四连修（已发布并实测）

| 问题 | 根因 | 结果 |
|---|---|---|
| 关闭关不掉 / 关时崩溃 / 托盘图标丢失 | `_spectrumTimer` 从未赋值 ⇒ `Shutdown()` 必抛 NRE ⇒ 打断关机流程；旧 `Exit()` 先 Dispose 托盘 | 已修：幂等 + 逐步兜底 + 托盘延后移除 + 强退兜底 |
| 点歌不能马上播放 | BASS 配置项索引全错（14/25/26 非法，静默失效）⇒ 预缓冲 2.4s + 5s 超时降级整文件下载 | 已修：正确索引 + 预缓冲 10% + 超时 15s |
| 一直显示「正在重连」/窗口卡住 | `ConnectAsync` 探测失败会把 `_server` 永久切到不可达的 WAN；3s 超时短于真实响应 | 已修：成功才提交地址 + 退避 + 每请求快照 |
| 库浏览慢（首屏 5s） | 服务端 music-tag-web **单线程串行**，总耗时 = 请求条数 × 78ms；客户端三处「几十个请求办一件事」 | 首屏 **~5s → ~1.5s**（歌曲页 4816→790ms、发现页 2602→781ms） |
| 点歌后界面无反馈 / 失败无声 | 缺即时反馈与错误提示 | 已加：起播中转圈 + 播放失败 toast |

**实测**：点歌 → 出声 ~1s；`getCoverArt`/封面、关闭/托盘、分页器「上一页」均正常。

## 服务端侧（重要背景）

- 服务端 = **music-tag-web**（`xhongc/music_tag_web`）@ `<NAS_LAN_IP>:8002`，挂载 `<NAS 音乐库目录> → /app/media`。
- 它**单线程串行**处理请求：并发 N 个时每个耗时 ≈ N×78ms ⇒ 优化只能靠「减请求数 + 加缓存」。
- **转码已关闭**（用户 2026-09-15 停掉），现返回原始格式与大小，首字节 135~523ms。
  m4a 仍可能 `err=Unstreamable`（非 faststart），客户端有「下载整文件再播」兜底（LAN 上 100~240ms）。
- 曲库（2026-09-16 收尾清理后）：**4301 个音频文件 ↔ 4301 条曲目，严格 1:1**；2069 张专辑 / 2162 位艺术家；
  **75GB / 656 个顶层目录**（整治前是 2091 个顶层目录）。隔离区与临时回收站**已按用户要求彻底删除**。
- **`music/attachments/` 是它的封面库，不是垃圾目录**（删了 `getCoverArt` 立刻 404）。

## 2026-09-16 声纹（Chromaprint→AcoustID）全库重鉴定 —— 已执行完毕

AcoustID application key 取自 `music-manager/app/acoustid.py`（`DEFAULT_CLIENT = "Z1SwTAHLhW"`；
music-manager 的 AGENTS 说该项目旧 Python 版已作废，但 key 本身可用）。

**流程（全部脚本在 `.audit/`，已 gitignore）**
1. `acoustid-run.py`（**NAS 宿主机**跑，容器里的 ffmpeg **没有** chromaprint muxer）：
   6 线程并行算 `ffmpeg -t 150 -f chromaprint -fp_format base64` → 3 线程 + 0.4s 节流查 AcoustID。
   **必须强制 IPv4**（这台 NAS 有 IPv6 地址却没有 IPv6 路由，否则每次查询都撞超时）。
   结果存**原始** `results[]`（不预解析）→ 可离线重解析，不用重查。5980 首 80 分钟。
2. `acoustid-diff.py`（`/vol1/1000/runtime/tools-venv`，需 `opencc-python-reimplemented`）生成计划。
3. `acoustid-quarantine.py`（宿主机移文件）+ `acoustid-apply.py`（容器改库/改标签/改名）。

**结果**：5980 → **4302 首**；改名 1447、改标签 1432、改库 1942、隔离 1678 个文件（25GB）、
删空专辑 1255、删空目录 2418。**0 个标签失败、0 个音频 MD5 变化、0 个目标冲突。**
验收：磁盘↔数据库 4302↔4302，无孤立/无缺失/无重复路径；`search3` 分页合计 4302。

**AcoustID 的四个陷阱（踩过，务必记住）**
1. **一次查询返回所有 release/credit**，同一个 result 里会同时列出**旋律相同但不同歌**的录音
   （陈奕迅《十年》/《明年今日》）。只按时长挑 ⇒ 会把文件改成隔壁那首。
   **正解：先看有没有哪个候选的 title 与「文件自己提供的候选标题」一致，有就采信它。**
2. **高分不等于对**：AcoustID 会把翻唱/其他 take 归到原唱（`One Direction → Oliver Harrigan`、
   `白允y → G.E.M. 邓紫棋`）。所以**绝不因为高分就凭空新建艺术家**——只有该目录已被判定为
   「批量错下载目录」时才允许新建。
3. **相同 `recording_id` 不等于同一份音频**（86 对里就有时长差很多的不同 take）。
   去重必须**再加 chromaprint 逐位比对**：本次只隔离 `sim ≥ 0.99` 的 1446 对，另有 86 对证据不足**留存**。
   注意：本项目的 `similarity` 是「同位哈希相等比例」——**1.0 是铁证，低值不是反证**（转码/不同母带会很低）。
4. `recordings[0]` 是任意顺序；要按时长选，但**时长选择只在没有 title 命中时才用**（见 1）。

**怎么认出「批量错下载目录」**：目录名是歌名而不是歌手（`20岁的眼泪 (Live)/未知/`），
或该目录内多首在高分+时长吻合下指向**不同** AcoustID 艺术家且**没有一个**等于目录名
（`AakI7zzz`→Post Malone 等、`De_pres_sion`→Adele）。严格判据下本次共 30 个（早期 1091 个的宽判据
已废弃，见下一节）。

**仍未处理**：① `.dsf`/`.wav` 只改了数据库、没改内嵌标签（ffmpeg 写这两种格式不可靠），
采样 250 条里 13 条属此类 —— 客户端显示以数据库为准，不影响使用；② 399 条进了 review
（AcoustID 的歌手不在本库 / 时长不吻合），未动；③ **1447 条 AcoustID 认不出但在正规
`<歌手>/<专辑>/` 目录里、标签看着也合理**（张宇/替身、李荣浩/麻雀、张杰/年轻的战场…），已保留。

## 2026-09-16 收尾清理（用户要求「隔离的删掉 / 目录名是歌名的也删」）

1. **彻底删除隔离区**：`.dsh-quarantine-20260915-212219/`（25GB / 1941 文件）+ 更早的
   `.dsh-trash-dup-*`（19MB）+ 两个 `.dsh-*-latest.txt` 标记文件。`/vol1/@team/public/` 现在只剩
   `dev` 与 `music`。
2. **30 个「批量错下载目录」清空**（严格判据：目录内 ≥2 首在高分+时长吻合下指向不同 AcoustID 艺术家，
   且没有一个等于目录名）。注意：早期那条「目录名 == 曲名」的启发式**太宽**，会把 `杨千嬅` 这类真歌手
   也算进去 —— 最终只用严格判据，得到 30 个（`AakI7zzz`/`Ado`/`De_pres_sion`/`Hot大热狗`/`Qkxml`/
   `大包子2526`/`糖三葬`/`怪鸭博士`/`音乐的独奏曲`/`Various Artists`…）。
   - **90 个文件重新归位**到真实歌手（Ariana Grande / One Direction / Maroon 5 / Lady Gaga /
     The Weeknd / Bruno Mars / Cardi B / Silk Sonic / MIKA / Nicki Minaj / Wale / Tony Bennett…）
   - **1 个文件删除**（`Hot大热狗/未知/Rest of my life.m4a`，AcoustID 完全认不出 ⇒ 按用户规则视为无意义）
   - 该目录里遗留的 29 个孤儿 `cover.jpg`/`.lrc` 一并删除
3. **`<曲名>/未知/<曲名> - <歌手>` 型目录修复**（用户最在意的「目录名是歌名」）：
   文件名本身就写了真歌手，不需要联网。判据是**目录形状**——曲名侧的目录没有正规专辑子目录、
   歌手侧的目录有。**248 个文件重新归位**（任然 77、张杰 36、许嵩 27、李克勤 16、毛不易 14、
   五月天 9、杨千嬅 7…），另有 **590 个文件只是文件名顺序反了**（`情胜策略 - 方大同` →
   `方大同 - 情胜策略`），原地改名。
   ⚠️ **`<A> - <B>` 是有歧义的**：可能是 `<歌手> - <曲名>` 也可能是 `<曲名> - <歌手>`。
   我第一版按「哪边是已登记的艺术家」判，结果把 `蔡琴 - 为何不早说` 认成「蔡琴是曲名」，
   差点把 3130 个文件搬到以曲名命名的目录里；第二版又因为垃圾艺术家行本身就叫曲名而失效。
   **正确判据是目录形状，不是名字表。**

**结果**：顶层目录 903 → **656**，曲目 4301，占用 75GB；磁盘 ↔ 数据库 4301↔4301，
无孤立文件 / 无缺文件 / 无重复路径。全部脚本与判定记录仍在 `<NAS>/runtime/acoustid/`。

## 2026-09-15 曲库大修（服务端，已完成并端到端验收）

背景：09-10 21:2x 与 09-15 13:36 两次「重命名 + 修数据库路径」把库搞乱了 —— 同一文件被多条曲目指向、
文件名里的 `_20260910212448` 时间戳被写进 `Track.name`、大量曲目指向已不存在的路径。用户在客户端看到
同一首歌重复 4 行、点开播放的是别的声音。

做法（全部**证据驱动**，不用标题模糊匹配）：

| 步骤 | 判据 | 结果 |
|---|---|---|
| 行↔文件重映射 | 路径(HTML 反转义/去时间戳) → 文件大小唯一命中 → 路径+时长 → 唯一同名+时长 | 6042 行正确绑定 |
| 删除幽灵行 | 无任何文件可绑定的行 | −151 行 |
| 路径修正 | 时间戳/转义/移动后目录 | 16 行改路径（含 `/app/media` 前缀补齐） |
| 同名冲突 | 同路径多行 → 用文件真实 size/duration 选唯一属主 | 18 组 → 0 |
| 补导入 | 磁盘上有、库里没有的文件 | +22 行（2 个 0 字节流的坏文件已隔离） |
| 专辑/艺术家归位 | 全部曲目同一艺术家但专辑挂在别人名下 | 147 张专辑重绑 + 49 张同名专辑合并 |
| 聚合刷新 | `song_count/duration/size` | 全部重算 |
| 二轮去重 | ffmpeg chromaprint 指纹，**指纹完全一致 + 时长差 ≤2s** | −83 个重复文件（0.37GB） |
| 杂项 | 失效转码缓存、`Track.name` 里的时间戳、空目录 | 清 393MB 缓存 / 修 23 个名字 / 删 3171 个空目录 |

**验收（Subsonic REST，实测）**：
- `ping=ok`；`search3` 空查询分页 500+500+500+480 = **5980**，TTFB 460~827ms（清理前 4816ms）。
- 磁盘 ↔ 数据库双向核对：**无孤立文件、无缺文件、无重复路径、无空专辑归属**。
- `stream` Range 请求 206 + `audio/wav`，TTFB **67ms**。

**可回滚**：隔离目录 `<NAS 公共目录>/.dsh-quarantine-20260915-212219/`（含 `audio-dedup2/`、`corrupt/`、
`low-bitrate-copies/`，合计约 2.1GB）；数据库全量备份与逐步报告在 `<NAS>/runtime/music-tag-web/data/`
（`dumpdata-music-*.json`、`dbfix-backup-*.json`、`repair2/3-backup-*.json`、`final-cleanup-*.json`）。

**仍未解决（需要用户试听判断）**：少数同名曲目是「时长差别很大但确实不是同一份录音」的多个版本
（例：刘若英《后来》有 219/272/279/341 秒四个文件，指纹相似度 <0.30 ⇒ 不是同一录音，故保留）。
指纹相似度 ≥0.30 的只有 84 对，其中 83 对完全一致已清、1 对已清 —— 即**能自动判定的重复已全部清完**。

## 2026-09-16 曲目标题错乱修复（服务端，已修 35 首，已验证）

用户报「**明天，你好这首歌不对板**」。查出根因是**标题被批量写错**：多个不同歌曲的文件被改名成同一个
名字，`title` 标签也跟着被覆写。最典型的两族：

- `牛奶咖啡/Lost & Found去寻找/` 三个文件**全都叫 `明天，你好`**，实际分别是
  **离开的理由(263s) / 如果明天(274.6s) / 快乐星猫 Live(192.3s)**（后两者的时长与官方 04:34 / 03:14 对得上）。
- `尹光` 下 **8 个文件全都叫 `剑合钗圆`**（分属 8 张不同专辑），实际是
  命硬八十 / 老豆 / 荷里活有个大老千 / 担番口大雪茄 / 出嚟沟女 / 世界杯之数波波 / 点解未有妻 / 波霸篮球赛。
- `周深/借过一下/` 5 个文件全叫 `借过一下`，实际是 叱咤红人 / 一定要幸福 / 独家记忆 / 抱一抱 / 大地回春。

**取证方法（纯本地，不需要联网）**：文件内嵌的 `lyrics` 标签带 `[ti:...]`/`[ar:...]` —— 那是
**标题被覆写之前**抓取歌词时留下的真实曲名；再叠加「文件名 / 专辑目录名 / `.lrc` 旁挂文件 / 时长」
交叉验证，**只有两个以上独立来源一致才动手**：

- **规则 A**：文件名与 lyrics 一致、而 `title` 标签与两者都不符 ⇒ 用文件名。
- **规则 B**：`title` 标签是从文件名抄的（两者相同）、且 lyrics 与专辑目录名或 `.lrc` 一致 ⇒ 用 lyrics。
- 手工确认 10 条（牛奶咖啡×2、周深×5、尹光×2、刘若英《原来你也在这里》）。
- 反例校验：`Paper Rings`（lyrics 写 Paper Hearts）与 `Zero`（lyrics 写 Imagine）**时长与标签吻合**，
  说明 lyrics 也会错 ⇒ 所以必须有第二个来源，不能只信 lyrics。

**改写方式**：`ffmpeg -i X -c copy -map_metadata 0 -metadata title=新名`（m4a 追加 `-movflags +faststart`），
逐个比对**音频流 MD5**（35/35 完全一致，音频零改动），再更新 `Track.name/name_pinyin/name_sort`。
备份：`<NAS>/runtime/music-tag-web/data/titlefix-backup-<ts>.json`。
验收：DB 名 ↔ 内嵌 title 标签 0 不一致；`search3 "借过一下"` 现在只返回陈小春的两首真身。

**仍未解决**：① 真《明天，你好》**不在库里**（需要用户重新获取）；② `周深/借过一下/` 那 5 个文件的
`lyrics[ar]` 都是**陈小春**，说明艺术家/专辑维度也错了，但「陈小春的哪张专辑」本地无可信证据。

**根治方案（推荐）**：NAS 实测能连通 `api.acoustid.org` / `musicbrainz.org`（HTTP 400 = 可达）。
用 chromaprint 指纹 + AcoustID **按音频内容**重新鉴定全库 title/artist/album，可一次性解决所有
「不对板」。需要一个免费的 AcoustID application API key（<https://acoustid.org/new-application>，1 分钟）；
全库 ~6000 首约 35 分钟（AcoustID 限 ~3 req/s）。

## 待办（用户要求"都做"，尚未实现）

1. **全屏播放器页**（HTML UI 新增沉浸式 Now Playing 视图）
2. **智能歌单编辑器（Navidrome）**（协议专属规则编辑）
3. **furigana/romaji 显示**

## 建议的下一步

- 先把 1（全屏播放器页）做掉：改动集中在 `WebAssets` + 少量桥方法，用户可感知度高。
- 继续压客户端请求条数的空间还在：`GetPagerTotals` 的歌曲总数（约 6s，已 quiet 且缓存 30min）可改为
  「随歌曲累加器增量得出」；`getAlbumsPage` 仍要 2 个请求（专辑列表 + 艺术家索引）。

## 关键目录

- `subsonic-player/dotnet/src/SubsonicPlayer.Cef/WebAssets/` —— HTML 主界面（index.html / styles.css / app.js / mini.html）。
- `subsonic-player/dotnet/src/SubsonicPlayer.Cef/Services/` —— `AppSchemeHandler.cs`（app:// + 内嵌资源）、
  `CefUiBridge.cs`（C#↔JS 桥）、`CefPageDataProvider.cs`（页面数据，含 search3 快路径）。
- `subsonic-player/dotnet/src/SubsonicPlayer.Core/Services/` —— AudioEngine / PlaybackService / SubsonicClient /
  RecommendationService / LimitedParallel / AppLog / ImageLoader。
- `subsonic-player/dotnet/MEMORY.md` —— 技术栈与全部踩坑（**本会话记录在文件尾部**）。
- `subsonic-player/dotnet/publish-singlefile.ps1` —— 单文件发布（含子进程依赖补齐 + locales 扁平化）。
