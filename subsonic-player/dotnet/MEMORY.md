# subsonic-player 项目记忆

> 项目记忆。完整设计方案见 `PLAN.md`。

## 一句话

面向 NAS 音乐服务的**专业级桌面音乐播放器**（Roon 风格 UI）。当前自用服务端为「**道理鱼音乐**」（Gonic）。

## 技术栈（已定）

.NET 10 + Avalonia 11.3.20 + Semi.Avalonia 11.3.14 + CommunityToolkit.Mvvm + SQLite；音频引擎 BASS + BASS_FX；**UI 为 HTML（Chromium 120 via CefGlue.Avalonia 120.6099.211，OSR 渲染）**。

> 版本统一决策：`Core`/`Cef` 用 Avalonia 11.3.20 + Semi 11.3.14；`Mobile` 原为 12.0.3，已**对齐到 11.3.20**（Avalonia 11.3.20 支持 Android/iOS），避免 Mobile 引用 Core 时的 Avalonia 版本冲突。Core 仍与 Avalonia 强耦合（`IImage`/`IBrush`/`DispatcherTimer` 等），彻底解耦是大重构，暂缓。

## 项目结构（app/dotnet/）

- `src/SubsonicPlayer.Core`：共享逻辑（BASS 音频引擎、PlaybackService、AppServices、Subsonic/Emby/Plex 客户端、歌词搜索）
- `src/SubsonicPlayer.Cef`：**主力**。HTML UI（WebAssets）+ CefGlue.Avalonia
- `src/SubsonicPlayer.Mobile`：移动端（规划中）
- `publish.ps1`：全平台发布脚本

## UI 技术（CEF 迁移，重要）

- **为什么降级 Avalonia 12→11.3.20**：CefGlue.Avalonia 120 依赖 Avalonia 11.0.9 编译，在 Avalonia 12 上运行崩 `TypeLoadException: Could not load type 'Avalonia.Input.GotFocusEventArgs'`（二进制不兼容）。11.3.20 正常。
- **Avalonia 11 兼容**：`TextBox.PlaceholderText`→`Watermark`；移除 `WindowDecorations`（12 新增）。
- **exclr8cef 已弃用**：NuGet 只有托管 DLL，native 缺失（runtime.*.Exclr8Cef 全 404、GitHub 无 release）。
- **HTML UI 架构**：
  - WebAssets/（index.html / styles.css / app.js，原生 JS 无框架，Roon 风格）
  - 自定义 scheme `app://ui/`（AppSchemeHandlerFactory + FileResourceHandler，新式 Open/Read 异步 API）
  - C#→JS：`CefUiBridge` 暴露 `window.bridge`（`RegisterJavascriptObject`，方法返回 Task 自动转 Promise），事件订阅后 `ExecuteJavaScript` 推送 `bridgeEvent` CustomEvent → JS `StateBridge`
  - JS→C#：`window.bridge.invokeData(method, argsJson)` 统一入口（反射分派到 `CefPageDataProvider`，UI 线程调度）；`Bridge.invoke(method, ...)` 直调 void 方法
  - 封面用 `GetCoverArtUrl` 带认证 URL 给 `<img src>`；播放走 BASS（JS 只发命令，进度由 C# 每 500ms 节流推送）
- **CEF 缓存**：`%APPDATA%\subsonic-player\cef-cache`，改 HTML/CSS/JS 后 UI 异常先清缓存
- **CEF 初始化**：Program.cs `AfterSetup` 里 `CefRuntimeLoader.Initialize(settings, cmdArgs, customSchemes)`，需 `WindowlessRenderingEnabled = true`（OSR）
- **窗口**：无边框（`ExtendClientAreaToDecorationsHint`），HTML 自绘标题栏 + 拖动/最小化/最大化/关闭按钮

## 已踩大坑（CefGlue + HTML UI）

1. **右侧"边框阴影"= queue-panel 的 box-shadow**：`position:fixed; right:0` 关闭时 `right:-340px` 移出屏幕，但 `box-shadow` 仍向左投射 → 右侧渐变阴影（深色不可见、浅色显形）。排查：空白页二分定位（曾用 CefShadowTest 诊断项目，已删除）。**修复：关闭时 `visibility:hidden`（仅位移不够）**。教训：fixed/absolute + box-shadow 移出视口仍投影，须 visibility:hidden。

2. **OSR 物理键盘无法输入（搜索框/设置表单）**：CEF 内部 input 可聚焦，但 **AvaloniaCefBrowser 未持有 Avalonia 键盘焦点** → KeyDown 不路由到 CEF → 物理键盘进不去（CDP 模拟能输入、SendKeys 物理键不行）。**修复：AttachBrowser 监听 PointerPressed，每次点击强制 `_browser.Focusable=true; _browser.Focus()`**。窗口 GotFocus/KeyDown 日志验证链路。

3. **EQ 无效两处**：
   - **频点非法**：`EqFrequencies` 原含 31/62Hz，**低于 BASS DX8 ParamEQ 最小中心频率（80Hz）→ `BASS_FXSetParameters err=IllegalParam`**。改 `{100,150,250,500,1K,2K,4K,8K,12K,16K}`（与 JS 滑块标签同步）。
   - **带宽太窄**：`fBandwidth=1.0`（半音单位，1/12 八度极窄无感）→ 改 **12f（1 个八度）** 后可闻。
   - EQ 挂在 mixer 上（`_mixer==0` 时跳过——未播放时拖 EQ 无效属正常）。

4. **歌词不显示/卡死**：
   - Gonic `getLyrics` 返回 404 **抛异常**，被外层 catch 吞掉 → Web 兜底从未执行。修复：server 歌词异常隔离，失败继续 `LyricsSearchService.SearchAsync`。
   - **LRCLIB 在大陆被墙**（连接挂起 10s 超时）→ 每次点歌词卡 10-20s。修复：lrclib 用 4s 短超时 HttpClient（`HttpFast`）+ 与网易云**并行**（`Task.WhenAny`），中文歌网易云 1-2s 返回。
   - 网易云 API 可用（`music.163.com/api/search/get` + `/api/song/lyric`，需 Referer），歌词缓存进 SQLite（lyrics_cache）。

5. **服务配置保存后不生效**：`SaveService` 只保存不重建客户端。修复：`AppServices.ReloadCurrent()`（重建 Music + 停播清队列 + 重载收藏 + 触发 `CurrentServiceChanged`）；JS `services` 事件里清数据缓存（pageCache/albumsCache/songsCache）+ `navigate(currentPage)` 重载。
   - **后续坑（本轮）**：`CefPageDataProvider.EnsureConnectedAsync` 原以「服务 id」作连接缓存键，而保存/切换服务会**重建 Music 实例（id 可能不变）**→ 新实例尚未 `ConnectAsync`（`SubsonicClient._server` 仍为构造函数初值，WAN-only 时为空串）却被当作「已连接」直接用于请求 → 页面全「连接失败/加载失败」。修复：连接缓存改为按 **Music 实例引用**（`ReferenceEquals`）判断，实例换了就重新 `ConnectAsync`。同理 `GetDiscoverQuick/GetDiscoverMore` 也补调用 `EnsureConnectedAsync`（否则新增实例首屏走通不到连接，不可达时默认 HttpClient 100s 超时拖死首屏）。
   - **JS→C# 增删改服务须可 await**：`SaveService/DeleteService/SwitchService` 原 `void` + `Dispatcher.UIThread.Post`，JS `await Bridge.invoke(...)` 立即返回、随后 `getServices` 读到旧列表 → 「新服务器不马上显示」。改为返回 `Task`（`PostOnUI`：`TaskCompletionSource` + `UIThread.Post`），JS 真正等到 save 完成再刷新。

6. **图标用 SVG symbol sprite** 替代 unicode/emoji（▶⏮🔁 等在 CEF 缺字闪烁）。`<svg width=0><defs><symbol id="i-xxx">` + `<use href="#i-xxx">`。

7. **侧面板关闭残留阴影/不可见**：queue-panel 打开需同时 `right:0` + `visibility:visible`（`toggleSleepMenu` 曾漏 visibility 导致睡眠面板点不开）。统一用 `closeSidePanel()`，点击面板外空白也关闭（document mousedown 判断）。

8. **浅色主题下 accent-text 看不清**：`html.light` 需覆盖 accent 系列变量（`--accent-text` 浅色用深青绿 `#0B6B4F`）；`.dtab.active`（青绿底）文字用深墨绿 `#06231B` 而非浅青。

9. **配置页密码明文**：`GetServices` 不返回明文密码（只给 `hasPassword` 布尔），编辑时密码留空=不修改。

10. **播放列表水平滚动条**：`queue-item` 的标题/艺术家被塞进无类名的中间 `<div>`（grid 子项），该 div 默认 `min-width:auto` 会按 `nowrap` 文本撑宽 `1fr` 轨道 → 行宽超出 340px 面板出现横向滚动。**修复：`.queue-item > div { min-width:0; overflow:hidden; }`**，并给 `.content`/`.queue-body` 加 `overflow-x:hidden`。教训：grid 里作为子项的「文本包裹层」必须 `min-width:0`（对比 `song-row` 每列自带 overflow:hidden 所以不溢出）。

11. **双击播放 ×2**：播放动作绑在 `click` 上，而鼠标双击会触发两次 `click` → 同一首连续加载两次。**修复：`playOnce(key,fn)` 400ms 去重**（同一 key 窗口内只执行一次），包裹所有 `click` 播放入口（song-row / album 播放按钮 / 章节 play-all / 书签 / 队列项）。注意 Windows 系统双击判定默认上限 ~500ms，去重窗口取 400ms 才可靠。

12. 12.5. **搜索框用不了输入法（中文打不出字）**：CefGlue.Avalonia 的 `AvaloniaCefBrowser` **不实现/不注册 IME client**，Avalonia 11 里 `TopLevel.TextInputMethod`/`SetClient` 是 internal，底层是 Windows TSF 输入法从未被激活 → 中文/日文/韩文输入法组合事件不路由到 CEF，搜索框只能打英文数字。**修复**：① 子类 `ImeAvaloniaCefBrowser : AvaloniaCefBrowser`，在 `OnGotFocus/OnLostFocus` 里通过 `RaiseEvent(InputElement.TextInputMethodClientRequestedEvent)`（`TextInputMethodClientRequestedEventArgs.Client` 填/清 `ImeTextInputMethodClient`）向顶层输入法**申请成为 client**；② `ImeTextInputMethodClient : Avalonia.Input.TextInput.TextInputMethodClient`（**注意：此 Avalonia 版本没有 `ITextInputMethodClient` 接口，只有抽象类**），`SupportsPreedit=true`，`SetPreeditText` 转发到 `CefBrowserHost.ImeSetComposition(text, 1, underline, range, range)`、`RequestReset`→`ImeCancelComposition`；提交文本走控件自带 `TextInput` 事件（基类已转发）插入。**注意**：`UnderlyingBrowser` 是 protected（子类可访问），`CefBrowserHost` IME 签名：`ImeSetComposition(string text, int underlinesCount, CefCompositionUnderline underlines, CefRange replacementRange, CefRange selectionRange)`、`ImeCancelComposition()`、`ImeCommitText(...)`、`ImeFinishComposingText(bool)`；`TopLevel.GetTopLevel(Visual)`/`TopLevel.TextInputMethod` 在本版本**不是 public**，故用路由事件法。**无法用 CDP 验证真实输入法**（`Input.insertText` 直插渲染层、绕过 TSF），只能验证不回归；真机仍需用户输一个汉字确认。**教训：CEF OSR 的 IME 必须用 Avalonia 的 TextInputMethodClient 机制把输入法挂到控件上，默认控件不做这件事。**

**OSR 下原生 `<select>` 弹窗不渲染（顶栏服务器下拉"打不开列表"）**：CEF 离屏渲染（WindowlessRenderingEnabled）时，原生 `<select><option>` 的**展开弹窗列表不会绘制**——DOM 里有 option、选中文本也正常显示，但点"▼"后弹窗空白/无反应。**修复：改用自绘 `<div>` 下拉**（与主题菜单 `#themeMenu` 同模式：`.service-select-wrap` = 按钮 + `data-open` 菜单，`renderServices` 填充 `.service-item` 按钮，点击调 `switchService`）。诊断法：CEF 远程调试（`--remote-debugging-port=9333`，用后移除）+ `Input.dispatchMouseEvent`/`Runtime.evaluate` 点开看 `data-open` 与 `display`。**教训：OSR 里凡是"点出列表"的原生控件（select/input 下拉），都要换成自绘 div 才可靠**——设置弹窗里的 `svcType`/`themeSelect` 也是原生 select，同属此坑，后续按需改。

## 发现页设计（重要）

- 顶部两个 **tab**：随机推荐 / 智能推荐，共用一个位置
- **每天只刷新一次**：`localStorage` 存 `discover_cache_{random|smart}` + 日期 key，跨天自动失效；「换一批」清当天缓存强制刷新
  - **坑：换服务器后发现页不刷新**：`loadDiscover` 由 `discoverTabLoaded`（会话标志，首载后直接 `return`）短路，且 `discover_cache_{random|smart}` 是**跨服务器**的当天缓存 → 切到另一台服务器，发现页仍显示旧服务器的内容。修复：`services` 事件（`CurrentServiceChanged`/开关流）里除清 pageCache/albumsCache/songsCache 外，还要 `discoverTabLoaded=false`（否则 navigate 后 loadDiscover 直接 return）+ `discoverMoreData=null` + `clearTabCache('random'/'smart')`。**注意**：其他列表页（专辑/歌曲/艺术家）走 `CefPageDataProvider` 的 `TtlCache` 以服务 id 为 key，切服务器自动失效无需处理；只有发现页有“已加载”标志 + localStorage 跨服务器缓存这两个额外坑。
- **从上到下顺序加载**：tab → 最新专辑 → 常听专辑 → 高分专辑；未加载区块全部**骨架占位**（skel-rows/skel-albums/skel-detail，固定点位防跳动）
- 数据源：`getDiscoverQuick`（随机歌）、`getDiscoverMore`（智能推荐 + newest/frequent/highest 专辑，一次返回全部）
- 专辑/艺术家/歌曲/收藏/历史/书签/搜索页加载均用骨架占位（`skelGridHtml`/`skelSongListHtml`/`skelDetailHtml`）
- **统一分页器（首页 \| 1 2 3 … \| 下一页）**：专辑/歌曲/艺术家/歌手页单曲/风格单曲五处都归到 `pagerHtml` + `PAGER` 状态表 + `pagerObserve/pagerMount/pagerGo`，`#content` 的点击委托里**分页器最先判断**（`[data-pager][data-page]`），否则会先命中列表行/卡片。**分页 = 整页替换**，不是往下追加；`setupInfiniteScroll()` 已刻意清空（两套心智模型混用会出现"点第 3 页，列表里同时有 1、2、3 页内容"）。总页数只有艺术家索引是**确切**的（`getArtists` 一次全取，C# 给 `totalPages`+`hasMore`），专辑/风格只有 `hasMore`，`count===0` 才是"这页不存在"的权威结论（`pagerObserve` 用它回退 `max`）。A-Z 跳转不再单独拉 `getArtistsAt`，而是 `page = floor(offset/100)+1` 走同一套分页，避免"网格是 D 段、分页器写着第 1 页"。**歌手页内部的歌曲行不给歌手名做链接**（`songRowHtml(s, {linkArtist:false})`）——整页都是一个歌手，点它等于点当前页；专辑卡/歌曲页/队列/底栏仍可点。验证：`tools/cdp-check-pager.mjs`（需临时开 9333，见 AGENTS.md）。
- **每页多少（页大小只有一处定义）**：**歌曲列表一律 10 首/页**（歌曲页、歌手页单曲、风格单曲），定义在 C# `CefPageDataProvider.SongPageSize`，**JS 不写这个数字**（只报页码，免得改一边忘一边）；专辑 20 张/页、艺术家 100 位/页（它们不是"歌"）。
  - **坑：服务端没有「全部歌曲」端点。** 以前 `GetSongsPage`「按专辑翻页」——一页 20 张专辑展开出来多少首看运气（实测 21 首），于是"一页"根本不是一页歌；`GetArtistSongsPage` 同理按专辑切（30 张专辑）。改法：新增 `SongFlattener`（`_flatSongsCache`，10 分钟 TTL，`_flatSongsLock` 串行化）+ `GrowFlatSongsAsync(key, need, nextAlbumBatch)`——**按需增长**一份扁平歌曲列表（要第 N 页就长到第 N 页所需首数，每轮多展开 `AlbumBatchSize=25` 张专辑），然后 `Skip((page-1)*10).Take(10)` **按歌切片**；`hasMore = acc.Songs.Count > skip + pageSize`（多要一首才判断得出）。累加器按 `lib|{serviceId}` / `art|{serviceId}|{artistId}` 缓存，翻页/回头页都命中同一份列表（实测歌曲页 1→5→2→1 内容逐首一致）。`SongFlattener.Add` 对非空 `Song.Id` 去重（合辑里同一首歌出现在多张专辑，不能占两个坑）。
  - 歌手页总数用专辑自带 `SongCount` 求和（不必把每张专辑都拉下来），因此 `totalPages` 是确切的（蔡琴 46 首 → `首页 1 2 3 4 5 下一页`，第 5 页 6 首、下一页变灰）；`GetArtistDetail` 的第一页单曲**走同一条累加器**切出前 10 首，保证"进页面看到的"与"翻回第 1 页"逐首一致。
  - **遗留**：歌手页的「播放全部」按钮其实只播当前这一页（`collectSongs` 只收 DOM 里已渲染的行）——分页之前就存在，10 首/页之后更明显，要修就得让 C# 侧按 artistId 整队播放。

## 其他功能要点

- **C# 源文件中文乱码（大坑）**：`SubsonicPlayer.Core/Services/PlaybackService.cs` 等源码曾被以错误编码保存，导致其中文字符串字面量（如 `CurrentTitle` 的「未在播放」占位、`PlayModeName` 的「随机播放/列表循环/单曲循环/顺序播放」）在源码里存成**双编码乱码**（如「未在播放」存成「鏈湪鎾斁」）。这些是用户可见字符串，编译后显示乱码。**排查**：`CurrentTitle` 在切服务器/无歌播放时返回乱码占位 → 播放栏乱码。**修复**：用 PowerShell 以 UTF-8 读文件 + 正则整行替换回正确中文（`edit` 工具对乱码字节匹配不到，用正则+`MatchEvaluator` 才稳）。**教训**：改动 Core 里的中文 UI 字符串前，先确认文件编码；用 `Select-String` 扫 `[\uE000-\uF8FF]` 可能漏掉纯 CJK 乱码，要用宽松的非 ASCII 字面量扫描。
- **Subsonic XML 一律按 UTF-8 解码**：`SubsonicClient.GetXmlAsync` 原来 `ReadAsStringAsync`，对无 charset 的 `Content-Type: text/xml`（Music Tag 就是）会回退 Latin-1，UTF-8 中文变乱码。改读字节 + `Encoding.UTF8.GetString`。
- **艺术家列表头像（网易云）**：多数 Subsonic 服务端（Gonic/Music Tag 等）不提供艺术家照片（`getArtistInfo` 无图/404），故 `ArtistImageService`（Core）按艺术家名查网易云 `music.163.com/api/search/get?type=100` 取 `artists[].picUrl`（需 `Referer: https://music.163.com`），内存缓存；`CefPageDataProvider.GetArtistPhoto(name)` 供 JS 调用。JS `initArtistLazyLoad` 滚动可见时先取照片，无图回退 `getArtistCover`（代表专辑封面），再无则保留首字母头像。**注意**：Music Tag 的艺术家列表本身混有歌名/专辑名（如「100表情」「20岁的眼泪」），应用如实显示；这些也会拿到网易云图，但名字仍非真艺术家——那是服务器元数据问题，应用无法纠正。
- **收藏（红心）**：`FavoritesService`（红心状态真相）+ `CefPageDataProvider.GetFavorites`（收藏页）原来只从「喜欢的音乐」歌单取收藏，而 **Music Tap（Music Tag）用 `getStarred`（歌曲级星标）**，没有该歌单 → 红心初始全灰、收藏页空。修复：① 新增 `IMusicService.GetStarredSongsAsync`（`SubsonicClient` 解析 `getStarred` 的 `<song>`）；② `FavoritesService.LoadAsync` 先取 `getStarred` 歌曲、再兜底「喜欢的音乐」歌单（并集）；③ `GetFavorites` 先 `getStarred`、再 `getAlbumList type=starred`、再歌单兜底。**注意**：Gonic 的 `getStarred` 实测也能返回（104 首），故两服务器都可兼得；写收藏仍走 `star/unstar`。
- **智能推荐在 Music Tap 没数据（发现页「智能」Tab 空白）**：`RecommendationService.GetRecommendationsAsync` 原来**只把「喜欢的音乐」歌单当收藏来源**，且**找不到该歌单就提前返回空**。Music Tap（Music Tag）没有该歌单（用 `getStarred` 歌曲级星标）→ 智能推荐恒为空。修复：① 收藏来源改为 `music.GetStarredSongsAsync()`（getStarred）∪「喜欢的音乐」歌单（并集，`DistinctBy(s.Id)`），且**不再提前返回空**（无收藏也用随机歌兜底）；② 步骤 4 按艺术家/专辑收集候选时，**单个 `GetArtistAlbumsAsync`/`GetAlbumAsync` 失败要 try/catch 跳过**——Music Tap 有些艺术家端点直接返回 HTTP 500（如 `art_6a05935e00a0546c`），原来一失败外层 catch 静默吞掉 → 整块推荐变空。修后实测 Music Tap `rec=10`、`favorites=3`、`artists=6`、`candidates=16-19`。**注意**：Music Tap 歌曲普遍无 `Genre`（`favGenres=0`），智能推荐主要靠「收藏+历史艺术家评分」，流派亲和加成失效属正常。
- **C# 源文件中文串乱码（保命）**：本机 `zh-CN` 文化下，**无 UTF-8 BOM** 的 `.cs` 文件里写中文**字符串字面量**，Roslyn 会按 GBK 读源码 → 编译出的字符串乱码（如「推荐」→「鎺ㄨ崘」）。**新增中文**字符串/注释到 `.cs` 前先给文件加 UTF-8 BOM（`[System.IO.File]::WriteAllText($f, $text, (New-Object System.Text.UTF8Encoding $true))`），或干脆用英文 ASCII。注释乱码不可见、可不管；字符串字面量（日志/UI）必须注意。本次已给 `RecommendationService.cs`/`ImeTextInputMethodClient.cs`/`ImeAvaloniaCefBrowser.cs` 加 BOM，日志改为英文 ASCII。**补充（2026-09 实测）**：本仓库多个 `.cs`（`CefPageDataProvider.cs`/`CefUiBridge.cs`/`SubsonicClient.cs`/`Album.cs` 等）其实**没有 BOM** 且含大量中文**字面量**，编译产物里这些字面量是**正确的**——写了 `tools/check-cs-encoding.ps1` 逐条验证（从源码取字面量、按 UTF-16 在 DLL 字节里搜，13/13 命中，未命中的 6 条全是注释里的引号）。结论：`.NET 10` + `dotnet build`（Roslyn 跑在 CoreCLR 上）无 BOM 也按 UTF-8 读源码；上面那条 GBK 现象请当作**旧工具链/未复现**对待，别据此改写整文件编码。**注意**：`.ps1` 反过来**必须** UTF-8 BOM（PS 5.1 按 ANSI 读无 BOM 脚本，中文直接语法错误，见 `publish-singlefile.ps1`）。
- **EQ 面板**：10 段滑块 + 预设；重开面板从 C# `GetEqGains()` 回显滑块与选中预设（匹配 presetGains 判定）
- **音量平均（客户端归一，2026 实现）**：原引擎只做「服务器 ReplayGain」（`Song.ReplayGainTrackGain`/`SubsonicClient` 解析 `replayGain*` 属性 + `SetStreamGain` 应用），但 **daoliyu(Gonic)/Music Tap 实测都不下发 `replayGain*`**（`<song>` 无该属性）→ 归一恒为 1x，形同没做。**修复：客户端按响度采样归一**——`AudioEngine` 加 `_normGain`（`EffectiveVolume = 用户音量 × ReplayGain × _normGain`），播放时 `BeginNormalization()`，进度节拍 `SampleNormalization()` 采样 **`BASS_ChannelGetLevel(_mixer)` 输出峰值**（**注意**：tempo 包装的源 channel 用 `BASS_Mixer_ChannelGetLevel` 读不到，要对 mixer 用 `BASS_ChannelGetLevel`，需在 `BassNative` 加该 P/Invoke），累计 ~20 次（约4s）算平均峰值，`gain = clamp(target(0.45)/avg, 0.5, 2.0)`，归一日志写 `playback.log`（`Normalize: avgPeak=... gain=...`）。设置「音量平均」开关（`AppSettings.VolumeNormalization`，默认 true；`SetVolumeNormalization` 桥 + 设置弹窗 `#volNormToggle`），关闭即复位 1x。**实测**：`Normalize: avgPeak=0.284 gain=1.58x`（把偏低的歌推高到目标）。
  - **★ 坑（用户报「推拉声音大小时，没有马上把声音大小改变」的真因）**：`BASS_ChannelGetLevel(_mixer)` 量的是**混音输出**，也就是**已经乘过用户音量**的电平。原代码直接拿它算增益 ⇒ `gain = target /(音量 × 源电平)` ⇒ 实际输出 = `音量 × gain = target`，**与音量无关**。于是归一化采样窗口（每首歌开头 ~4 秒 / 20 次采样）里，用户拖音量条完全没反应（被归一化补回去了），4 秒后 `_normDone=true` 增益固定、音量才恢复正常 —— 症状就是"不是马上变，过一会儿才变"。**修复**：采样时先除以当前有效音量，把电平还原成**源电平**（`peak = raw / EffectiveVolume()`），归一化只负责歌与歌之间的响度差；`EffectiveVolume() <= 0.02` 时跳过采样（除以接近 0 的值会把噪声当信号）。日志同时打出 `vol=` 便于核对：改后同一首歌 `vol=0.80 → srcPeak=0.275 gain=1.64x`、`vol=0.20 → srcPeak=0.325 gain=1.38x`（源电平与音量无关 ✓；改前第二个数字会变成 ~0.07 且 gain 被抬到 2.0 上限）。
  - **顺带发现（未改）**：`BassMixNative.BASS_Mixer_ChannelSetEnvelope` 的 P/Invoke **少一个参数** —— 原生是 `(handle, type, nodes, count)`，声明写成了 `(handle, nodes, count)`，所以 `CrossfadeTo` 里设的淡入/淡出 envelope **从来没生效**（crossfade 实际是 `MixerNoRampIn` 直接起播、旧歌等 `RemoveChannelAfterAsync` 到点移除，没有渐变）。真要修得连 `BASS_MIXER_ENV_VOL` 的语义一起确认（若 envelope 会**压过** `BASS_ATTRIB_VOL`，那修好之后必须在淡入结束时撤掉 envelope，否则音量条会在每首歌之后失效）。修它=改变可听行为，先记着不动。
- **非 faststart M4A 播不了（下载降级）**：部分歌曲（如 Music Tap 的「Midnight」「旧欢如梦」，`suf=mp4a.40.2`）的 M4A 文件 `moov`（codec 信息）在**文件末尾**（非 faststart）。BASS 的 `BASS_StreamCreateURL` 从前往后流式解码、读不到末尾 codec 头 → `err=47(CODEC)` 无法解码；而本地文件能读完整结构（`BASS_StreamCreateFile` 能解）。**修复**：`AudioEngine.CreateStream` 在 URL 流失败（err=47）时**降级为「用 HttpClient 下载整个流到临时文件，再 `BASS_StreamCreateFile` 播」**，临时文件记入 `_tempFiles`（`Stop()/Free()` 清理）。注意 `BassNative` 需加 `BASS_StreamCreateFile` P/Invoke（`bool mem, string file, long offset, long length, BASSFlag`）。仅对失败的网络流触发，其余歌曲不受影响。
- **BASS AAC 播放（bass_aac）**：部分服务器（Music Tag/Music Tap）只出 `audio/x-m4a`（AAC/MP4），BASS 核心无 AAC 解码器 → `BASS_StreamCreateURL err=47(CODEC)` 播不了。修复：把 `bass_aac`（un4seen `files/z/2/bass_aac24.zip` / `-linux.zip`，x64）放进 `native/{win-x64,linux-x64}`，并在 `AudioEngine.LoadPlugins` 数组加 `"bass_aac"`。**注意**：① `LoadPlugins` 只按 `{name}.{ext}` 找，Linux/macOS 插件是 `lib{name}.{ext}`，必须加 `lib` 前缀兜底（Windows `bassflac.dll` 无 lib 前缀）；② un4seen 无 macOS 版 bass_aac（只有 win/linux/android），mac 仍不能放 AAC；③ AAC 版权/许可见 addon 内 gpl.txt。
- **播放进度点**：updatePlayerBar 需同时更新 `fill.width` 和 `thumb.left`（曾只更新 fill 导致圆点不动）
- **队列封面占位**：无封面/加载失败用 `#i-music` SVG（span 需 `display:flex` 否则 grid 列塌陷错位）
- **书签行**：点击走 `playBookmark`（曾缺 data-song-id 无反应）
- **睡眠定时器**：C# DispatcherTimer 到点 Pause；JS `pickSleep` 高亮选中 + 提示"已设置 N 分钟"
- **多服务下拉**在顶栏（serviceSelect）；设置弹窗管理服务器增删改
- **风格（流派）浏览（2026 新增）**：Music Tap 没有歌单，加了「风格」页替代。链路：`IMusicService.GetGenresAsync` + `GetSongsByGenreAsync`（`getSongsByGenre?genre=&count=&offset=`）→ `CefPageDataProvider.GetGenres`（`_genresCache` 120s，按服务 id 缓存；返回 `supported` 标志）/`GetSongsByGenre(genre,page,pageSize)`（一次取 500）→ JS 导航项 `genres`（图标 `#i-genre`）/`loadGenres`（卡片网格 `.genre-card`）/`openGenreDetail`。
  - **关键坑：getGenres 的流派名解析**。规范里流派名有**两种**返回形式——`value` **属性** 或**元素文本**。`<genre value="Pop" songCount=".."/>` vs `<genre songCount="5778" albumCount="3344">未知</genre>`。**Music Tag Web/Music Tap 用「元素文本」**，最初只读 `value` 属性 → 名字全空被过滤 → 误判「服务器没有风格数据」。修复：`Name = (el.Attribute("value")?.Value ?? el.Value ?? "").Trim()`。**教训：解析 API 字段时属性/文本两种形式都要兜。**
  - **地址回退（重要）**：Music Tap 配了 **两个**地址——`LanUrl=http://192.168.1.10:8002`（家里 LAN，不在同一网段时不通）+ `WanUrl=http://music.example.com:8002`（Tailscale）。`SubsonicClient.ConnectAsync` **内网优先、失败回退外网**是既有实现且有效。排查时**别只 ping LanUrl 就下「服务器离线」结论**——要用 `WanUrl` 也探一次（这也是当时误判「Music Tap 不在线」的原因）。
  - **实测（经 WAN）**：Music Tap 支持 `getGenres`/`getSongsByGenre`，`getGenres` 返回 15 个流派（未知 5778 首、wusunk.com收藏 184、Blues 42、Pop 20…；名字较脏是**服务端标签数据**问题）；`openGenreDetail('Blues')` 返回 42 首。**daoliyu（Gonic，此版本）不支持**（`getGenres`/`getSongsByGenre` 404、byGenre 过滤被忽略、专辑/歌曲无 `genre`）→ 页面显示友好提示。
  - **UI 坑**：`#pageGenres` 容器**不要再套 `class="genre-grid"`**（JS 会自建内层 `.genre-grid`，否则嵌套网格导致卡片挤成一列 179px）。
  - **曲库流派数据实况（Music Tap，2026 实测）**：整个库约 6300 首，`getGenres` 只有 15 项，且**九成是「未知」**——`未知 5778`（未打标）、空串 118；**剩下的是下载站水印**：`wusunk.com收藏/分享/合购`、`WUSUNK.COM`、`kuwo`、`海精灵音乐美图论坛`、`","`、`"."`（共 7 项 ~247 首）。真流派只有 `Blues 42 / Other 34 / Musiques du monde… 28 / Pop 20 / Alternative 14 / pop-folk 10 / C-Pop 1`，且里面也掺错（Blues 里是陈楚生/哆啦A梦）。**根因**：文件是从国内论坛/下载站拿的，`genre` 字段被写成了**来源站名**（例：`/app/media/邓丽君/www.520hjl.com海精灵音乐美图论坛/…ape` → genre=`海精灵音乐美图论坛`；`/app/media/朴树/KU#WO (Explicit)/…` → genre=`kuwo`）。**App 侧处理**：`CefPageDataProvider` 加了 `IsJunkGenre`（正则过滤 `http/www./.com|cn|net…/wusunk/kuwo/论坛/分享/合购/下载/收藏/资源/美图/无损/hires/dsd` + 纯标点）与 `IsUnknownGenre`（未知/Unknown/Other… 沉底），`GetGenres` 返回 `filtered` 数量，前端 `.genre-note` 提示「已隐藏 N 个来源水印流派」。**局限**：只是**清理显示**，救不了「92% 没流派」——想要真流派需在文件上重打标（MusicBrainz Picard/beets 指纹）或做 App 内外部数据源补全（未做）。
- **服务端是 Gonic**：认证用 `p` 明文密码（不支持 token error 41）；`album.artist`/`year`/`track` 返回 Go 内存地址或 `<nil>` 脏数据；`getArtists` 含非法 XML 控制字符需清理；id 前缀 `alb_`/`trk_`/`art_`

## 跨平台（已就绪）

- **BASS**：`native/{win-x64,osx,linux-x64}` 三平台 9 库（bass + flac/opus/ape/wv/dsd/midi/fx/mix）；`LibraryExtension` 按平台 `.dll/.dylib/.so`；BassNative `Lib="bass"` 跨平台可用
- **CEF**：NuGet redist 按 RID 注入。注意发布产物结构差异：win `libcef.dll` 根目录、linux `libcef.so` 在 `CefGlueBrowserProcess\`、osx `libcef.dylib` 根目录
- **窗口拖动**：`#if WINDOWS` Win32（ReleaseCapture + WM_NCLBUTTONDOWN）；macOS/Linux 用 Avalonia `BeginMoveDrag`（OSR 缓存 `PointerPressed` 的 `PointerPressedEventArgs`）
- **托盘/窗口图标**：Windows 用 `.ico`，macOS/Linux 用 `.png`（`Assets/avalonia-logo.png`，平台判断资源名）
  - **图标改成自包含音乐图标（2026）**：原先一直用 **Avalonia 框架自带 logo**（透明底上的蓝色剪影 `#0D6EFD`）——透明镂空透壁纸、蓝底无对比，当 app 图标很丑（曾试过「给剪影描白边」救，更怪，已废弃）。**定论：app 图标要做「自包含」** —— 自带底色、不依赖壁纸，通行做法是**圆角方块「应用瓷砖」+ 一个简洁符号**（Windows11/macOS/iOS 同套路），**靠底色对比、不靠描边**。现图标：**深色圆角方块底（`#2B313A`→`#12151A` 竖向渐变，留 6.25% 边距、圆角 23.5%）+ 青绿 `#2DD4A7` 双八分音符 ♫**，用 `System.Drawing` 矢量绘制（每个尺寸**直接按比例画**、不做位图缩放；**小尺寸把符号加粗**：≤24px ×1.20、≤32px ×1.10，避免 16px 糊）。.ico 用 **BMP 条目（16~128）+ PNG 条目（256）**；`.png` 32×32 同款。`Assets\**` 全量 `AvaloniaResource` 内嵌，**改图标后必须重编译**（exe 图标记在 `<ApplicationIcon>`）。**注意：换 exe 图标后 Windows 图标缓存会让旧快捷方式仍显示旧图标**，需刷缓存（`ie4uinit.exe -show` 或删 `%LocalAppData%\IconCache.db` 后重启 explorer），或重建快捷方式。旧的 Avalonia 图标备份在 `D:\work_space\_icon_bak\`。
- **DPAPI 密码**：仅 Windows 注入（`OperatingSystem.IsWindows()`）；其他平台 AES-GCM
- **SMTC/全局热键**：`#if WINDOWS` 保护；非 Windows 走 Noop 兜底
- **发布**：`app/dotnet/publish.ps1 -Platform win-x64,linux-x64,osx-x64,osx-arm64`（self-contained 默认），自动校验 BASS/CEF/WebAssets

## 跨平台待办/注意

- **osx-arm64**：`native/osx` 的 BASS dylib 是 x64 → arm Mac 需 Rosetta 2
- **Linux 系统依赖**：BASS 需 ALSA（libasound2）；CEF 需常见图形库
- **真机验证**（无 mac/linux 环境）：OSR 渲染、非 Windows 拖动（PointerPressed 是否触发）、托盘、音频输出
- **Linux 无边框拖动**：`BeginMoveDrag` X11 OK，Wayland 受限
- **macOS 分发**需签名 + notarization

## 调试技巧

- **CEF 远程调试**：Program.cs 临时加 `--remote-debugging-port=9333` cmdArg，用 raw CDP（Node WebSocket 连 `http://127.0.0.1:9333/json` 的 page target）eval JS / 看 console / 模拟输入。Playwright `connectOverCDP` 会报 "Browser context management is not supported"（CEF 限制），用 raw CDP。
- **物理键盘测试**：CDP focus input 后 `WScript.Shell.SendKeys` 模拟真实键盘，验证 Avalonia→CEF 链路。
- **日志**：bridge.log（CefUiBridge 操作）、provider.log（CefPageDataProvider）、eq.log（AudioEngine EQ 调试）、crash.log（未处理异常）、cef.log（Chromium）
- **WebAssets 改动**后清 `cef-cache` 重启，否则显示旧缓存

## 服务器连接

- 地址分内网/外网，连接时内网优先、不可达回退外网（`SubsonicClient.ConnectAsync`）
- 多服务器配置持久化（settings.json，密码加密存储），不硬编码、不提交仓库
- **断线重连（新增）**：① `SubsonicClient.GetXmlAsync` 对**网络层瞬时错误**（`HttpRequestException.StatusCode==null`/Socket/IOException/超时）**自动重连并重试一次**（内部 `ConnectAsync` 重选可达地址；`AsyncLocal<int> _retryDepth` 防止 Connect→Ping→GetXmlAsync 递归；带 StatusCode 的 4xx/5xx 是服务器返回错误，**不**重连）。② `CefUiBridge` 加**连接监听 Timer**：连接中每 30s/断开每 6s 调 `music.ConnectAsync()` 健康检查，状态变化时 `Push("connection",{connected})` 给 JS。③ JS `StateBridge.on('connection')`：断开显示顶部「正在重新连接服务器…」横幅（`#connBanner`），恢复后隐藏横幅并 `navigate(currentPage)` 重刷当前页（详情页除外，数据已在 DOM）。**注意**：`EnsureConnectedAsync` 的 `_connectedMusic` 缓存不影响——数据请求由 `GetXmlAsync` 自愈，断线恢复由监听器推送事件驱动。**验证法**：临时把服务器 LAN 地址指到本地不监听端口（如 `127.0.0.1:8523`）+ 起一个只回 `<subsonic-response status="ok"/>` 的 HttpListener，可端到端测「断线→banner 显示→起服务→banner 隐藏（重连）」。**注意**：空 XML 会让部分解析（如 `GetRandomSongs` 期望 `<randomSongs>`）抛错，属测试假象，真服务器正常。

## 多服务支持

- 协议：Subsonic 族（Subsonic/Navidrome/Jellyfin/Gonic）+ Emby + Plex 已实现；AudioStation 已接入（认证+URL 完成，浏览待真机验证）
- 扩展点：`MusicServiceFactory.Create` 按 `MusicServiceConfig.Type` 分支
- **设置页「服务类型」下拉**：`SaveService` 现接收 `type` 参数并持久化 `MusicServiceConfig.Type`；`GetServices` 返回 `type`。此前保存不设类型（恒为 Subsonic），Emby/Plex/AudioStation 无法在 UI 选择——现已可切。Navidrome/Gonic/Jellyfin 走 Subsonic 兼容（`SubsonicMusicService`），无需独立客户端。
- 曲库规模：约 2174 艺术家 / 28 个字母索引

## UI 布局规范（多次返工）

- 所有列表/网格行 Grid 列宽**必须固定宽度**，**禁止 `*` 自适应列宽**（标题伸缩导致右侧列错位，用户多次反馈）
- 容器窄（如 320 面板）时缩小固定列宽而非改用 `*`

## 近期改动（2026-08 一轮）

- **Song 模型加 `Genre` 字段**：`SubsonicClient.ParseSong` 读 `genre`（Emby/Plex 默认空）。智能推荐据此做**流派亲和重排**（收藏热门流派靠前）。
- **智能推荐增强**：`RecommendationService` 用「收藏权重2 + 历史权重1」艺术家评分、每艺术家配额（保多样）、流派亲和排序。`getSimilarSongs2`/`getGenres` 需跨协议扩展 `IMusicService`，未引入（Gonic 的 OS 支持不确定）。
- **KTV 歌词**：侧面板歌词 `.lyric-line` 卡拉OK式（当前行放大高亮 + 前后行渐隐），`updateLyricHighlight` 按 `data-start` 高亮并滚动居中；播放进度更新时触发。
- **动态封面底图**：`index.html` 加 `.bg-layer`（封面模糊 + 遮罩），主内容区 `--bg-main-glass` 半透明透出；播放时 `bgImg.src` 同步当前封面（详情页由 `setDetailBg` 换成该专辑/歌手封面）。深浅主题各一套玻璃色。
  - **2026-09 调过一次"更清"**：原来是 `filter: blur(46px) saturate(1.25); opacity: .65; transform: scale(1.15)` + 固定深色遮罩 `rgba(10,10,12,.32→.5)` + `--bg-main-glass` 0.52 —— 出来是一团灰，封面是什么根本看不出来（用户："毛玻璃底图这个效果不太好，底图要再清一点"）。现在：`blur(12px) saturate(1.15) brightness(1.04)`、`opacity .9`、`scale(1.08)`（scale 只为盖住 blur 外溢边，不用 1.15）；遮罩改成按主题的 `--bg-scrim-1/2`（深色主题 0.10→0.28，**浅色主题必须是浅色** `rgba(245,245,247,.06→.30)`——沿用深色遮罩会把浅色主题的封面压成脏灰）；`--bg-main-glass` 深色 0.34 / 浅色 0.62。**调法**：想更清就降 `blur`（8/6/4，0 = 原图）或降 `--bg-main-glass`（0.30/0.20，实测 0.20 时歌曲行文字开始和底图打架）；想更糊就加回去。截图对比用 `tools/cdp-shot.mjs` + CDP（模板见 `tools/cdp-check-pager.mjs` 的头注释）。**注意**：只调 `.bg-img` 不够——侧栏/顶栏自己带 `backdrop-filter: var(--blur)`（24px），透过去的底图还会被二次模糊。
  - **坑：封面背景“没生效”（根本看不到）**：`.bg-layer` 是 `position:fixed; z-index:-1`，而 `html, body { background: 渐变 + var(--bg-app) }` 给 **body 也涂了一层不透明背景**；CSS 绘制顺序里 `body`（正常流块）的背景画在**负 z-index 层之上**，于是 body 的不透明背景把 `.bg-layer` 整体盖住 —— 封面图其实加载了（`naturalWidth>0`、`complete`），但从头到尾被 body 挡住。**修复：`body { background: transparent; }`**（把底色/渐变只留在 html 画布层，负 z-index 的 `.bg-layer` 才能浮在其上）；浅色覆盖从 `html[data-theme="light"] body` 改到 `html[data-theme="light"]`。**诊断法**：CDP `getComputedStyle(document.body).backgroundColor` 若为不透明（rgb 三值）即中招；`.bg-img` 需 `naturalWidth>0` 才确认图真的加载。
  - **可见度调校**：封面透出度 = `bg-img opacity` × (1 − 暗角) × (1 − `--bg-main-glass`)。历史：opacity0.45 + ::after(0.62/0.84) + main(0.66) 叠乘只剩 ~3%，肉眼像没生效；再到 opacity0.65 → 0.9；**当前** opacity **1** + 上下暗角(0.04/0.34) + 列表页 main 0.46 / 沉浸页 16%。
  - **2026-09 第二次调：对齐「手机播放器那种整屏封面」**（用户发来参考截图："我要这样的背景效果"）。参考里的底图 = **同一张封面铺满整屏、只是轻轻失焦**（封面里的大字、构图都认得出），不是灰雾。改法：`opacity: 1`、`filter: blur(11px) saturate(1.35) brightness(1.0)`、`transform: scale(1.12)`（放大让底图比中间的封面更"近"）；压暗层从"一条到底的均匀遮罩"改成**上下重、中间轻的暗角**（`--bg-scrim-2` 在 0%/100%，`--bg-scrim-1` 在 24%–68%）。**别用 `brightness()` 提亮**：封面亮部会被推爆，列表小字全被吃掉。
  - **底图分两档（关键）**：底图清晰后，列表页的小字（歌手/时长）压不住封面亮部（白衬衫、白底封面）——实测歌曲页的歌手名和时长基本看不见。按页面分两套底色：**沉浸页**（`nowPlaying` + 各详情页）= `body.immersive .main-area { background: color-mix(in srgb, var(--bg-app) 16%, transparent) }`（几乎不压，就是参考那张整屏封面）；**列表页** = `--bg-main-glass` 0.46（压得住小字）。`immersive` 类由 `app.js` 的 `showPage()` 按 `IMMERSIVE_PAGES` toggle —— 挂这里是对的，因为 `navigate()` 进详情页会先 `showPage('placeholder')`，随后 `openXxxDetail` 才 `showPage('xxxDetail')`。另给"直接浮在底图上的文字"加了 `--text-shadow`（深色主题黑投影 / 浅色主题白投影），比把底图重新压暗更保效果。
  - **调法汇总**：更清 → 降 `blur`（8/6/4，0=原图）或降 `--bg-main-glass`；更糊更稳 → `blur(20-30px)` + `--bg-main-glass` 0.52+；沉浸页透度改 `body.immersive .main-area` 那个 16%。截图对比用 `tools/cdp-shot.mjs`。**注意**：只调 `.bg-img` 不够——侧栏/顶栏自己带 `backdrop-filter: var(--blur)`（24px），透过去的底图会被二次模糊。
- **"点歌换歌很迟钝"的真因：播放状态每秒被推 ~20 次**（用户："点歌曲换歌那么难呢？"）。`PlaybackService` 是 ObservableObject，`CefUiBridge.OnPlaybackChanged` 对**任何**属性变化都调 `PushPlayback()`；而 `Spectrum` 被一个 **50ms 的定时器**每秒改 20 次，于是每秒发生 20 次「JSON 序列化 + `ExecuteJavaScript` + 写一条 bridge.log + 前端 `updatePlayerBar` 整轮 DOM 更新」—— UI 线程和渲染进程一直被占着，点击就排队，表现就是"点了半天界面才反应/很难换歌"。**而 `Spectrum` 全工程只有写入、没有读取**（无 XAML 绑定、无桥方法、JS 里也没有），payload 里也根本没有它 ⇒ 这 20 次推送**100% 是白做的**。
  - 修法：① `OnPlaybackChanged` 里直接忽略 `Spectrum`；② `PushPlayback` 加**签名比对**（签名覆盖 payload 的全部字段，含 `isFavorite`），只有前端真会用到的字段变了才推；③ 收藏那条路径用 `PushPlayback(force: true)`（收藏变了但没改任何 PlaybackService 属性）；④ `PlaybackService` 里那个 **50ms 频谱定时器停掉**（省掉每秒 20 次 UI 线程 FFT），要用它做可视化时再打开。
  - 实测：改前 CDP 里 `bridgeEvent('playback')` **每秒 20 条**、bridge.log 被 `PushPlayback cover:` 刷屏；改后 **bridge.log 只有 7 行且没有 PushPlayback**，点第 3 行歌曲 → 底栏标题**立刻**变成那首歌（18ms 内收到状态推送），再点另一首同样立刻切换。
  - **仍未解决**（另一个"换歌慢"的来源）：`playback.log` 里有 `CreateStream: URL 流失败 err=InitTimeout，已降级下载临时文件播放` —— BASS 网络流起不来时会**把整个文件下载到临时文件再播**（十几秒起步），而且同一首歌会重复触发多次。要再快得从 BASS 的网络超时/缓冲（`BASS_CONFIG_NET_TIMEOUT`/`BASS_CONFIG_NET_BUFFER`）或"失败时并行下载而不是等超时"入手。
- **分页器"一进来就把页码画全"**（用户："多页的地方，刚进来为什么只显现了一页的1，其他的页数也应该要显示出来"）。原来只有艺术家/歌手页有确切总页数，专辑/歌曲/流派都只有 `hasMore` ⇒ 刚进来只能画"首页 1 下一页"。现在把总数也拿到（**都不需要额外请求或只多一次行走**）：
  - **专辑数** = 艺术家索引里每个艺术家的 `albumCount` 求和（索引本来就整份取回并缓存）—— 实测 `sum(albumCount)=3502`，与逐块走 `getAlbumList2`（500/块，8 次）数出来的 **完全一致**，所以零额外请求。
  - **歌曲数** = 逐块走专辑列表累加每张专辑的 `SongCount`（服务端单次上限 500，约 8 次请求 / 1MB），结果 6252；缓存 30 分钟（`_albumTotalCache`/`_songTotalCache`）。
  - **流派歌曲数** = `getGenres` 里该流派的 `songCount`（那份列表已缓存）⇒ 零额外请求。Blues 45 首 → 5 页，与实测一致。
  - 新桥方法 `GetPagerTotals()` 返回 `{albums, songs, albumPages, songPages}`；前端 `ensurePagerTotals()`（会话内一次，换服务器清标记）**与列表并行**拉，回来只更新 `PAGER.albums.max` / `PAGER.songs.max` 并重画分页器，所以列表不用等它。`GetAlbumsPage`/`GetSongsByGenre` 也各自带上 `total`/`totalPages`；`AlbumPageSize=20` 提成常量与 `SongPageSize=10` 并列。
  - **顺带**：右边那个 `…` 改成**跳到末页**（左边那个仍是往回翻一格）—— 专辑 176 页、歌曲 626 页，只靠"下一页"根本走不到后面。为此 `…` 必须带 `data-page`（原来 `ellipsis: true` 会把 `data-page` 去掉、点不动是**设计如此**），CSS 的 `.pager-btn.ellipsis` 也去掉了 `cursor: default`。实测点 `…` → 第 176 页、2 张专辑（3502 = 175×20+2 ✓）。
  - **已知代价**：**歌曲页的深页码很慢** —— 服务端没有"全部歌曲"端点，翻到第 N 页要把前 N 页所需的专辑全部展开（末页 = 3502 次 `getAlbum`）。专辑/艺术家/流派页不受影响（服务端分页或已缓存索引）。要在歌曲页做深跳得另想办法（本地建索引）。
- **主题恢复"一点色相" + 拉开强调色**（用户："只拉开强调色，恢复一点主题色相"）。此前为了让面板不变成"贴在封面上的牛皮"，我把 5 个深色主题的面板/控件/文字**全部**收敛成同一套中性值，副作用是**深邃黑 ≈ 森林绿**（只差一个几乎同色的青绿强调色）。现在按"薄 + 带一点主题色相"重建：
  - **大面积透明色**（`--panel-glass` 侧栏/顶栏/播放栏、`--bg-main-glass` 内容区、`--list-veil` 歌曲列表整块、`--bg-card` 卡片/输入框）用**主题墨色**：深、低饱和、只带一点色相，不透明度保持薄（.45/.24/.42/.72）。例：森林绿 `rgba(12,24,16,…)`、午夜蓝 `rgba(12,16,36,…)`、落日橙 `rgba(30,20,12,…)`、玫瑰紫 `rgba(28,14,26,…)`、深邃黑 `rgba(12,13,17,…)`（冷调）。⇒ 底图照样透得出来，色相是"染"上去的一层而不是一块实体。
  - **加亮层保持中性白**（`--border`/`--border-strong`/`--bg-hover`/`--bg-active` = 白 13%/24%/8%/14%）：这些是"往上加光"，带色相会把墨色的层次拉花。
  - **强调色拉开**：深邃黑 `#2DD4A7`→**`#2DD4BF`**（冷青），森林绿 `#34D399`→**`#4ADE80`**（草绿，和冷青差 30°），午夜蓝 `#60A5FA`、落日橙 `#FB923C`、玫瑰紫 `#F472B6` 不变。文字色也跟着各自偏一点色（`#D5E4D9`/`#D6DCF0`/`#E6D8C7`/`#E8D3E1`）。
  - **结构**：末尾那段"共享覆盖"**已删掉**（它以前覆盖 `--bg-card`/`--border`/`--text-secondary`…，特异性 `:not()` 比主题块高，导致"改主题块不生效"）。现在每个主题的完整一套都写在它自己的 `html[data-theme=...]` 块里，末尾只留规则和一句"别在这里加覆盖"。改主题请改主题块。
  - 验证：CDP 逐个切主题读 computed style，6 套的 accent/墨色/描边/文字都对上；并截了 6 张图对比。
- **全局：三块主面板「外圈贴窗口边 + 中间也粘边」= 一整面（当前形态）**。演进：①"右手内容那个框四四方方的，死板" → 加圆角；②"三个框都和窗口边对齐、中间可以不粘边" → 外壳 `padding: 0` + `gap: 10px`；③**"中间还是粘边吧" → `gap: 0`，三块合成一整面**。做法是把它做成**改一个变量就能翻面**：
  - `--panel-gap: 0px`（三块主面板之间的缝；填 10px 就回到"中间留缝"那版）
  - `--chrome-radius: 0px`（主面板朝内的圆角；填 14px 配上面那版。**缝为 0 时圆角必须也是 0**，否则相邻圆角之间会漏出底图）
  - `--overlay-gap: 10px` / `--overlay-radius: 14px`（右侧滑出的队列/歌词/EQ/睡眠浮层，它不是那三块主面板，始终留缝+圆角；JS `panelGapPx()` 读的是 **--overlay-gap**，不能读 --panel-gap，否则粘边时浮层会贴死窗口边）
  - `.app-shell { padding: 0; gap: var(--panel-gap) }`；`.sidebar/.main-area/.topbar/.player-bar` 的 `border-radius` 全部走 `var(--chrome-radius)`（各自只给"朝内"的角，朝窗口边的角恒为 0）
  实测（1200×800）：sidebar(0,0,200,712)、main(200,0,1200,712)、player(0,712,1200,800) ⇒ 缝恒为 0、四周贴窗口边、圆角 0。
  **没有缝之后，区域边界只能靠底色明暗差**（这也正是把底色改成中性的价值：加深只变暗、不换色相）：
  - 默认底色：主面板（侧栏/顶栏/播放栏）`rgba(11,11,13,.62)`、内容区 `.44`、沉浸页 `color-mix(--bg-app 14%)`；浅色主题 `rgba(250,250,252,.62)` / `.50`。
  - `body.immersive .main-area` 仍然是"几乎不压"（正在播放 + 各详情页）。
- **列表文字在"什么封面都可能"的底图上要看得清**（用户："这些文字在不同的背景下如何能显示好。要处理一下"）。三层一起上，缺一层都不够：
  1. **亮度上限**（最关键）：`body:not(.immersive) .content { backdrop-filter: brightness(0.55) saturate(1.05) }`。它压的是**透过来的底图**的亮度，而不是再叠一层黑 —— 亮度是乘法、色相饱和度原样保留，所以底图还是那个颜色（面板继续保持中性、和封面同色系），只是不再有亮到吃字的区域；深色封面本来就暗 ⇒ 几乎看不出差别，只有亮封面被压。**沉浸页（正在播放/详情页）不加这一层**，保持"整屏封面"。选它而不是"把面板底色再加深"的原因见下一条（加深会把侧栏/内容区的明暗差吃掉）。
  2. **文字提亮**：深色主题 `--text-secondary: #DCDCE4` / `--text-muted: #B4B6C0`（原来是 #C9C9D3/#9EA0AB，在封面亮部上不够）；浅色主题反向用更深的灰。
  3. **两层投影**：`--text-shadow: 0 1px 2px rgba(0,0,0,.85), 0 0 8px rgba(0,0,0,.45)` —— 紧的一层勾字形、外扩一层压花的背景。
  **验证方法（可复用）**：把底图换成**纯白**（最坏情况）——`canvas` 画 16×16 纯色 → `toDataURL` → 设给 `#bgImg`（同源 data URL 不会被 taint，比网上抄的 base64 "1x1 白"可靠：那些很多其实是**透明**的，会退化成"没有底图"，什么也验不出来）。实测纯白底图下歌手名/时长全部清晰可读；再用真封面复核，并确认 `body.immersive` 时 `.content` 的 `backdrop-filter` 为 `none`。
- **全局：面板底色「中性 + 更透」，不跟主题色相**（用户："浮起的面板不好看。因为色彩不调和。好像一张牛皮贴在上边"）。原因：各主题的面板底色是**带色相**的（落日橙 = 棕 `rgba(30,22,14,.82)`、午夜蓝 = 深蓝…），82% 不透明压在封面上几乎不透视 ⇒ 面板成了贴在底图上的一块异色皮革。改法：CSS **末尾**追加一段（必须在各面板定义之后，同优先级靠后者生效）——`.sidebar/.topbar/.player-bar/.queue-panel { background: rgba(11,11,13,.62) }`、`.main-area { background: rgba(11,11,13,.44) }`（列表页）、`body.immersive .main-area { color-mix(in srgb, var(--bg-app) 14%, transparent) }`，浅色主题单独一套近白。因为面板本来就有 `backdrop-filter: blur(24px)`，透上来的是**糊过的封面**，面板颜色自然等于封面的色系 ⇒ 天然调和。**关键认识**：底色一旦中性，加深只会让底图变暗、不会换色相 —— 所以"压得住小字"和"和底图同色系"不再互相打架，以前那种"要可读就得把底图压死"的取舍消失了。主题身份改由强调色（`--accent`）和 html 底色承担。
- **次要文字中性亮灰**：深色主题 `--text-secondary: #DCDCE4` / `--text-muted: #B4B6C0`（原来那套带色相的暗灰 `#A1A1AA`/`#6B6B76` 会被封面亮部吃掉）；**必须排除浅色主题**（选择器用 `:not([data-theme="light"])`，浅色反过来用更深的灰，套亮灰会直接看不见）。
- **「太暗」= 透光度问题，不是主题问题**（用户："太暗了。这个暗是感觉的暗。不是说不要暗黑主题。" → 紧接着点明："这个暗是透光度造成的暗。"）。**含义：观感的暗来自"透过来的光被掐掉"，不是黑色本身** —— 所以解法是**提高透光度**，而不是换亮色主题。三处一起放开：
  - 面板底色变薄：深色主题主面板 `rgba(11,11,13,.62)` → **.45**、内容区 `.44` → **.24**。
  - 亮度上限放宽：`body:not(.immersive) .content` 的 `backdrop-filter` 从 `brightness(0.55)` → **`brightness(0.80)`**。**注意**：brightness 压的就是透光度，0.55 等于把整块内容区的光掐掉一半，观感必然发闷；它只该用来削亮部尖峰。
  - 文字的对比度改成**由文字自己承担**：`.song-row` 自带一层薄纱 `--row-veil`，`--text-shadow` 改成**紧贴字形的暗晕** `0 0 3px rgba(0,0,0,.85), 0 1px 2px rgba(0,0,0,.90)`（暗晕不挑方向，亮底暗底都成立）。
  **这条折中的来历（重要）**：用全局 brightness 压到能保证对比度（≈0.55）就必然"发闷"；反过来放开透光度，亮封面上小字又会糊。唯一同时成立的办法是**把压暗放到文字所在的那一条局部**（行纱）——页面/底图其余部分保持亮。实测：纯白底图下歌手名/时长依旧清晰，同时侧栏/顶栏/播放栏/列表以外的区域都是透亮的。
  - **列表改成"一整块透底"**（用户："这个列表很丑，你还不如改成一整块的透底的"）：~~逐行铺纱~~ → `.song-list` 整块 `background: var(--list-veil)` + `border-radius: 12px` + `padding: 4px 0`，`.song-row` 恢复透明（只有 hover `var(--bg-hover)`、正在播放 `var(--accent-soft)` 才上色）。**`.song-list` 的 `gap` 必须是 0** —— 留 2px 会从行缝里透出下面更亮/更暗的页面，看起来像一圈圈细横杠，就不是"一整块"了。变量 `--row-veil`/`--row-veil-hover` 已并入 `--list-veil`（深色 `.42` / 月光白 `.62`）。改完在纯白底图（最坏情况）下复核过：整块深色、字全部清楚，页面其余部分仍是亮的。
  - **浅色主题（月光白）必须整套反过来做**（用户："这是月光白的主题，总体透光没做好，就造成了灰灰的，暗哑无光。其实主题因为主体暗，所以不明显"）。我一开始把深色主题那套（**深色**行纱 + `brightness(0.80)` 压亮度 + 薄面板）直接共用给浅色主题，结果深色纱压在浅底上 = 一片灰、`brightness` 再压暗 20% = "暗哑无光"。改成：行纱方向跟文字颜色走（`--row-veil` / `--row-veil-hover`：深色主题 `rgba(10,10,12,.40/.52)`，**浅色主题 `rgba(255,255,255,.55/.78)`**）；**浅色主题不加 brightness**（`html[data-theme="light"] body:not(.immersive) .content { backdrop-filter: none }`），它靠更实的白面板（`.72` 主面板 / `.60` 内容区）保证深色文字可读；`--text-shadow` 也跟着反成白色暗晕。**规律：深色主题 = 薄面板 + 压亮度尖峰 + 深行纱；浅色主题 = 实白面板 + 不压亮度 + 浅行纱。两者不能共用一套数值。**
- **控件/卡片底色同样中性化**（用户截图指出顶栏搜索框、服务器按钮："这两块能不能也处理一下？"）。`--bg-card` / `--border` 原本也是**带主题色相**的（落日橙 = 棕 `rgba(40,30,20,.9)`），90% 不透明压在封面上和"牛皮面板"是同一个毛病，而且和旁边已中性的面板不是一个色系。同一段追加里一起改：深色主题 `--bg-card: rgba(22,22,26,.72)`、`--bg-hover: rgba(255,255,255,.08)`、`--bg-active: rgba(255,255,255,.14)`、`--border: rgba(255,255,255,.13)`、`--border-strong: rgba(255,255,255,.24)`；浅色主题整套对应（近白卡 + 黑系描边/悬停）。**强调色（`--accent`/`--accent-soft`）不动**，主题身份靠它。实测顶栏搜索框/服务器按钮/换一批/分页按钮的 computed background 都是 `rgba(22, 22, 26, 0.72)`、边框 `rgba(255, 255, 255, 0.13)`。
- **月光白（light）整体重做**（用户："月光白这个主题不对，有问题。你看着重做一下。按照其他的来参考"）。之前的状态是**两处各写一半**：主题块里一套旧值（`#F5F5F7` 底、`rgba(255,255,255,.85)` 面板、`#6B6B76` 文字…），文件末尾的中性化段又覆盖了其中一部分（`--bg-card`/`--border`/`--text-*`），两边不一致 ⇒ 观感既灰又难调。重做后的结构（**这就是"按照其他主题来参考"的落点**）：
  - **每个主题只在自己那个变量块里给值**，末尾那段规则里**不写任何主题数值**。为此新增两个变量：`--panel-glass`（侧栏/顶栏/播放栏底色）与 `--content-filter`（列表区那条 `backdrop-filter`），加上已有的 `--bg-main-glass`（内容区）、`--row-veil`/`--row-veil-hover`、`--text-shadow`、`--bg-scrim-1/2`。规则只写"谁用哪个变量"。
  - 月光白全套：`--bg-app: #ECEFF4`（冷调浅灰，**不用纯白**——纯白配玻璃会显脏）、`--panel-glass: rgba(250,251,253,.70)`、`--bg-main-glass: .56`、`--bg-card: rgba(255,255,255,.80)`、hover/active `rgba(16,18,24,.06/.12)`、border `rgba(16,18,24,.10/.20)`、文字三级 `#14161C / #474B55 / #767A86`、强调 `#0FAE85` / 文字用深青绿 `#06614A`、`--overlay: rgba(238,241,246,.72)`、scrim `rgba(238,241,246,.10/.42)`（浅色压暗层＝把封面往白里洗，即"月色"）、`--row-veil: rgba(255,255,255,.66)`、`--content-filter: none`。
  - 顺手修的两处浅色主题下的硬编码：`.artist-link:hover` 用 `var(--accent)`（浅底上对比不足）→ 改 `var(--accent-text)`；`.nav-list` 滚动条写死的 `#2a313c/#3a4350` → 改 `var(--border)`/`var(--border-strong)`（否则月光白下是一根黑条）。
  - 验证：月光白下截了 歌曲/专辑/正在播放/设置弹窗 四处 —— 冷白玻璃、行是白色霜状带、深色字清楚，弹窗是干净白卡；并用 computed style 核对 `--bg-app=#ECEFF4`、面板 `rgba(250,251,253,.7)`、行纱 `rgba(255,255,255,.66)`、`backdrop-filter: none`。
- **全局：不画分隔线，靠背景明暗分区**（用户："最好是全局的那种，全个软件不要太多的分隔线……这样就不会显得太死板"）。删掉的线：`.sidebar` 的 `border-right`、`.topbar` 的 `border-bottom`、`.player-bar` 的 `border-top`、`.sidebar-bottom` 的 `border-top`、`.queue-panel` 的 `border-left`、`.queue-header`/`.modal-header` 的 `border-bottom`、`.eq-presets` 的 `border-bottom`、`.settings-version` 的 `border-top`、`.genre-card` 的 `border`（改成和专辑/艺术家卡一样只靠背景块 + hover 变亮）。**保留**的线只给真正需要边界的元素：输入框、按钮、弹窗、下拉菜单、提示横幅、分页按钮。CSS 顶部写了这条原则，新增 UI 默认不加 border；区域分隔用「`--bg-surface` 0.82 ↔ 主区底色的背景差 + 间距」，队列面板保留 `box-shadow` 作浮起提示。**注意**：删线后仍需确认区域边界看得出来——改完用 `tools/cdp-shot.mjs` 截 专辑页/风格页/设置弹窗/队列面板 四个位置核过。
- **SMTC 缩略图**：`SmtcService.UpdateCover` 改用 `InMemoryRandomAccessStream`（原 `using MemoryStream` 转流会在异步读缩略图前被释放，导致缩略图不显示）。
- **CI workflow**：`.github/workflows/build.yml`，矩阵 win/ubuntu/macos 真机构建 + 发布 + 上传 artifact；Android 因 Mobile 占位暂不接入。
- **AudioStation（群晖）**：`MusicServiceType.AudioStation` + `AudioStationMusicService`（`MusicServiceBase` 子类）。已实现 SYNO.API.Discover + SYNO.API.Auth 登录拿 sid、封面/播放/下载 URL 构造，以及 **Synology.AudioStation.Song.list 分页聚合的曲库浏览/艺术家/专辑/搜索**（按已知 API 字段形状映射，解析失败降级为空）。**字段路径需在真实 DSM 上确认**（无设备前未实测）。

## 近期改动（2026-08 一轮）

- **Avalonia 彻底解耦（Core）**：`SubsonicPlayer.Core` **已不引用 Avalonia**（移除 `PackageReference Avalonia`）。
  - 封面管线 `IImage→byte[]`：`ImageLoader` 返回字节、`PlaybackService.CurrentCover` 为 `byte[]?`、`IMediaIntegration.UpdateCover(byte[])`、`SmtcService.UpdateCover(byte[])` 直接写缩略图流。
  - 计时器：`DispatcherTimer→UiTimer`（Core `System.Threading.Timer` + `IActionDispatcher` 抽象）；Cef 注入 `AvaloniaUiDispatcher`（`Dispatcher.UIThread.Post`）。
  - 删除死代码：20+ XAML 遗留 ViewModel + `ThemeManager` + `MainWindowViewModel` + `MiniPlayerViewModel` + `SpectrumHeightConverter`；Cef 不再用 Core `MainWindowViewModel`（DataContext）。
  - Core 现只有 `SongItemViewModel` + `ViewModelBase` 两个 VM；`SongItemViewModel.Cover` 为 `byte[]?`。
  - 跨平台 `/` Windows / Core 均编译通过。

## 近期改动（2026-08 一轮）

- **CI（GitHub Actions）**：`.github/workflows/build.yml`，`push(main)/PR` 真机编译三平台（win-x64/linux-x64/osx-x64），`push tags:v*` 触发 **GitHub Release 自动发版**（挂载三平台 zip）。
  - 每 OS 显式 `restore/build/publish -r <rid>`：Restore 用 `-p:TargetFramework=<tfm>` 限定当前 TFM，避免 Linux/macOS 还原 Cef 的 `net10.0-windows` 专属 TFM（会失败）。
  - **macOS 关键坑**：runner 是 **arm64**，默认 RID=osx-arm64 会找不到 CEF 原生 `libEGL/libGLESv2/libvk_swiftshader`（MSB3030）。项目 BASS/CEF 原生为 **x64**，故 macOS 必须 **`-r osx-x64`**（arm 上跑需 Rosetta 2）。
  - `publish --self-contained` 三平台已跑通；违规提示 `NU1903 SQLitePCLRaw 高危漏洞`（可后续升级 Microsoft.Data.Sqlite 消除）。

- **跨平台编译门禁**：本次 CI 抓出的真 macOS/多 TFM 问题均已修复（见上）。目的：Windows 交叉发布无法代替真机编译。

## 来源项目

播放器服务的音乐库/Subsonic 服务托管在自建 NAS。

## 当前状态 / 待办（2026-08 一轮：黑屏排查→单文件化→网络优化→线程→图标→CEF 加载/配置→主题）

### 当前可运行的部署
- **单文件版**：`D:\tools\SubsonicPlayer-single\`，`SubsonicPlayer.exe`（~253MB，全部应用托管代码 + .NET 运行时），CEF 原生（libcef.dll/resources.pak/icudtl.dat/snapshot/chrome_*.pak/locales\*.pak/CefGlueBrowserProcess）与 BASS（`lib\`）全外置。
- 旧的非单文件自包含 `D:\tools\SubsonicPlayer` **已删除**（单文件是现行方案）。
- CI 的 **Windows 构建已改单文件**（`publish-singlefile.ps1`）；Linux/macOS 仍非单文件（CEF 子进程修复只在 Windows 验证过）。
- 构建脚本：`app/dotnet/publish-singlefile.ps1` / `.bat`（**必须 UTF-8 BOM**，否则 Windows PowerShell 5.1 中文注释乱码→解析错误→CI 失败）。

### 本轮踩坑与定论（重要，防再踩）
1. **黑屏/闪退不是 GPU**：之前加 `disable-gpu/use-angle=swiftshader` 反而黑屏，**一律不加任何 GPU 参数**。
2. **WebAssets 内嵌**：`WebAssets` 以内嵌资源（`AppUI.*`）服务（`AppSchemeHandler` 用 `GetManifestResourceStream`）。**`ResourceHandler.Read` 必须判流空**（CEF 读到 EOF 后还会再调 Read，流已释放则 `ObjectDisposedException` 闪退）——已加空判 + 全方法 try/catch。磁盘/内嵌皆可，当前用**内嵌**。
3. **单文件 + CEF 的核心坑**：`PublishSingleFile=true` 会把 CEF 子进程（`CefGlueBrowserProcess\Xilium.CefGlue.BrowserProcess.exe`）需要的**托管 dll（System.*、Xilium.CefGlue.* 等）打进主 exe**，子进程缺依赖起不来 → CEF 报「GPU process isn't usable / exit 0x80004005」。**修复**：发布后从一次「非单文件自包含发布」把完整 `CefGlueBrowserProcess` 目录拷回目标（`publish-singlefile.ps1` 已内置）。locales 还要**扁平化到根 `locales\`**（ResolveLocalesDir 优先根 locales）。
4. **BASS 在 `lib\`**：csproj `<Content Include="$(NativeDir)\*"><Link>lib\%(Filename)%(Extension)</Link>`；`BassBootstrapper`（`[ModuleInitializer]`）启动时按绝对路径预加载 `bass/bass_fx/bassmix`（`NativeLibrary.Load`），否则 `DllImport("bass")` 找不到 `lib\` 下的库。该 `ModuleInitializer` 触发 **CA2255 警告（无害）**。BASS 归属（Core vs Cef）**未迁移**，保持「调用在 Core、原生文件在应用项目」。
5. **首播卡 UI 冻结**：播放入口（CefUiBridge `Dispatcher.UIThread.Post`）把 `PlayQueue` 派到 UI 线程，`PlayCurrent/PreloadNext` 里的 `BASS_StreamCreateURL`(网络)+`BASS_Init` 阻塞 UI 线程。**修复**：`CreateStream` 挪到后台线程（`Task.Run`），回主线程经 `AppServices.UiDispatcher.Post` 播放+更新状态。下一首本来就因为 `PreloadNext` 预建了流所以不卡。
6. **exe 图标**：csproj 加 `<ApplicationIcon Condition="Windows TFM">Assets\avalonia-logo.ico</ApplicationIcon>`（此前 exe 无图标，显示默认）。
7. **网络慢优化**：BASS `NET_BUFFER=3000ms`/`NET_PREBUF=-1`/`NET_CONNECTTIMEOUT=15s`/`NET_READTIMEOUT=30s`；`ImageLoader` 加磁盘缓存(`%APPDATA%\...\cover-cache`)+15s 超时+Retry；`TtlCache`(60s) 缓存艺术家/专辑列表/播放列表；`GetSongsPage` N+1 改限并发(4)；`DownloadService` 超时 5min；`Retry.DoAsync` 通用重试。
8. **cef 加载方式（学 OutSystems WebView）**：`app://` 的 CustomScheme 设 `IsStandard/IsSecure/IsFetchEnabled`（HTML UI 的 localStorage/Canvas 更稳）；`CefSettings` 加 `BackgroundColor`(深色防白闪)+`UncaughtExceptionStackSize`；退出 `CefRuntime.Shutdown()`。
9. **localStorage 在 `app://` 下会抛 SecurityError**（即使设了 standard/secure）→ 访问 localStorage 的 JS **必须 try/catch**，否则 `init()` 中断→页面无内容（已修）。
10. **CefGlue 120 差异**：`CefResponse` 无 `SetHeader`（Cache-Control 没做成）；`CustomScheme` 无 `IsCORSEnabled`（只用 IsStandard/IsSecure/IsFetchEnabled）。

### 自定义强调色主题（本轮新增）
- 设置弹窗「外观→强调色」5 套（青绿/紫/玫红/琥珀/蓝），每套深/浅两档。
- 经 CSS 变量覆盖 + `localStorage` 持久化；`init()` 里 `initAccentThemes()`；浅/深切换时 `StateBridge.on('theme')` 里重应用。

### 待办（用户要求"都做"，尚未实现 —— 建议在新会话继续）
1. **全屏播放器页**（HTML UI 新增沉浸式 Now Playing 视图）
2. **智能歌单编辑器（Navidrome）**（协议专属规则编辑）
3. **furigana/romaji 显示**

### 下一步开发时的注意
- 改 `WebAssets`(HTML/CSS/JS) → `node --check app.js` 校验 → 清 `%APPDATA%\subsonic-player\cef-cache` 再重启（否则旧缓存）。
- 单文件版重建：`powershell -ExecutionPolicy Bypass -File app/dotnet/publish-singlefile.ps1`。
- 主目录：`D:\tools\SubsonicPlayer-single\`；改动 WebAssets 需重编译（内嵌资源）。


## 2026-09-15 会话：桌面端四连修（关闭崩溃 / 托盘丢失 / 起播慢 / 库浏览慢）

> 本轮的坑都属于「看起来像网络问题、其实是客户端自己」这一类，值得反复读。

### 1. 关闭崩溃 + 托盘图标丢失（同一个根因）
- `PlaybackService` 的 `_spectrumTimer` 声明 `readonly`，但**唯一赋值语句被注释掉** ⇒ 永远 null；
  `Shutdown()` 里 `_spectrumTimer.Stop()` 必抛 `NullReferenceException`。
- 连锁反应：`App.ReleaseResources()` 抛异常 ⇒ `_desktop.Shutdown()` 永远执行不到 ⇒
  **窗口不关、进程不退、BASS 继续放歌**；而旧 `Exit()` 头两行是 `_trayIcon?.Dispose()` ——
  托盘图标**在抛异常之前就被删了** ⇒ 用户看到「窗口还在但托盘没了」，连托盘菜单都点不到。
  再点系统 ✕ 时 `_isExiting` 已是 true ⇒ `Closing` 不拦截 ⇒ 真关窗 ⇒ `HandleWindowClosed` 又抛 ⇒
  异常穿出 WndProc ⇒ 进程崩溃（事件日志 0xe0434352 / 0xc000041d）。
- 修法：删死字段；`Shutdown()` 幂等 + 逐步 try/catch；`ReleaseResources()` 拆 `ReleaseAudio`/`ReleaseTray`；
  `Exit()` 只停音频就发关机请求、**托盘留到 `desktop.Exit` 才删**；再加 4s 强退兜底 + 关闭按钮 6s watchdog。
- 教训：**退出路径上任何一步抛异常都会吞掉后面的清理**；托盘这类「退路 UI」必须等关机真的开始后再移除。
- 排查线索：`%APPDATA%\subsonic-player\crash.log` + Windows 事件日志（Application Error）。

### 2. BASS 配置项索引写错 ⇒ 静默失效
- `BASSConfig` 里 `NetPrebuf=14 / NetConnectTimeout=25 / NetReadTimeout=26` —— **这三个在 bass.h 里都不存在**，
  `BASS_SetConfig` 返回 false 且返回值被忽略 ⇒ 整套「慢网优化」从未生效。
- 后果：起播预缓冲仍是默认 80%（3s 缓冲 ⇒ 先憋 ~2.4s 才出声）；连接超时仍是默认 5s ⇒
  服务端慢一点就 `BASS_ERROR_TIMEOUT(40)`，掉进 `DownloadToTemp`「下载整个文件再播」。
- 正确值：`NetTimeout=11 / NetBuffer=12 / NetPrebuf=15 / NetReadTimeout=37`；
  现用 `NetBuffer=5000 / NetPrebuf=10 / NetTimeout=15000 / NetReadTimeout=30000`。
- 错误码名字也纠正过：**47 是 `UNSTREAMABLE`**（非 faststart 的 MP4），不是 CODEC。

### 3. 服务端是 music-tag-web，**单线程串行**处理（决定一切优化方向）
- 8002 = `xhongc/music_tag_web:latest`；容器挂载 `/vol1/@team/public/music → /app/media`。
- 实测同一请求：1 路 145ms；4 路 313ms×4；6 路 471ms×6；8 路 633ms×8
  ⇒ **每请求耗时 ≈ 并发数 × 78ms：并行不省总时间，总时间只由「请求条数」决定。**
- 所以优化只有一个方向：**砍请求条数 + 加缓存**。

### 4. 三处「用几十个请求办一件事」（已修：首屏 ~5s → ~1.5s）
- **歌曲页**：原来「翻专辑列表 + 逐张展开曲目」（本库 newest 专辑大多只有 1 首，凑 10 首要 26+ 请求）
  → **`search3?query=` 空查询就是「全库歌曲分页」**，支持 `songCount/songOffset`，**1 个请求**（4816ms → 790ms）。
- **发现页随机**：代码硬编码「Gonic 不支持 getRandomSongs」，走 1+10 个请求的变通
  → 本服务端**支持**（10 首 / 196ms），1 个请求（2602ms → 781ms）。
- **智能推荐**：6 艺术家专辑 + ≤12 专辑详情 = 18 请求
  → 官方 **`getSimilarSongs2`**（1 请求/种子）够用时就不走老路（带回退，3419ms → 1678ms）。
- 另外：列表 TTL 60s→5min、发现页结果缓存 3/5min、连接探测 single-flight（避免 6 个启动请求各探一次）。
- 实现位置：`IMusicService.GetSongsPageAsync/GetSimilarSongsAsync`（默认不支持，Subsonic 系 override）。

### 5. `attachments/` 是 music-tag-web 的封面库，**不是垃圾目录**
- 全盘统计「子树内无音频」的目录 = 11329 个，但**顶层只有一个：`music/attachments`**
  （`attachments/xx/yy/zz/*.webp`，4012 个 webp / 569MB）。
- 搬走后立刻实测：`getCoverArt?id=al-216` → **HTTP 404**，`al-1` → 404 ⇒ 确认是封面库。
- 结论：清理「没音乐文件的目录」**必须排除 `attachments/`**，否则客户端封面全挂。

### 6. 曲库现状与重复来源（排查用）
- 100GB / 15032 文件 / 6350 音频文件；6252 条库内曲目。
- 码率分布：**<80kbps 4527 首（72% 曲目）却只占 9.6GB**；flac 835 首/34.7GB、wav 738 首/43GB、dsf 69 首/11GB。
- 重复**不是转码造成的**（转码输出从未入库，库内 mp3 = 0）；是**重下/替换**留下的：
  同一首歌并存两种命名规范（`歌手 - 歌名` vs `歌名 - 歌手`）+ `_20260910212448_2` 这类「不覆盖另存」后缀。
- 「重复文件中删低码流」的正确判据：同歌手 + 同标题 **+ 时长一致(±3s)** + 排除未打标签(未知)
  + 保留方无损或 ≥200kbps + **无损绝不删**。不加时长校验会大量误判
  （实测误判出 `Girls Like You` vs `Shiver`、翻唱致敬专辑 vs 原版）。

### 7. 运维坑
- **`publish-singlefile.ps1` 必须在 `subsonic-player/dotnet` 下执行**。在后台作业里没传工作目录时它会
  **静默失败**（PowerShell 找不到 `-File` 的脚本，只打印 banner 就退出）——我因此拿旧二进制测了两轮、
  得出「优化无效」的错误结论。**部署后务必校验 exe 时间戳 + 搜符号确认新代码在。**
- 构建/发布要 `AVALONIA_TELEMETRY_OPTOUT=1`，否则 Avalonia 遥测任务写 `%LOCALAPPDATA%` 失败导致构建报错。
- NAS：`ssh NAS`（config 里指向 NAS 的 Tailscale 地址），局域网亦可直接连 NAS 的 LAN 地址；`ffprobe`/`docker` 都有。
- **从受限环境启动客户端会无声崩掉**（写 `%APPDATA%\subsonic-player` 被拒 ⇒ 连日志都写不出来，进程直接没）。
- 删曲库文件后要**在 music-tag-web 里重扫**，否则库内留下 `stream` 返回 404 的幽灵条目（客户端会弹播放失败提示）。


## 2026-09-15 会话（下半场）：服务端曲库大修 + 二轮指纹去重

### 1. 症状与根因
- 客户端「同一首歌 4 行」「点开播放的是别的声音」不是客户端 bug，是 **music-tag-web 数据库与磁盘脱钩**：
  - 09-10 21:2x 一次重导入（行号 ~4700-5099）与文件带 `_202609102124xx` 时间戳；
  - 09-15 13:36 一次「去时间戳重命名 + fix-dbpaths 模糊修路径」把 `Track.path` 写到了错误文件上，
    **导致 18 组「多条曲目共用一个 path」、146 条曲目指向不存在的文件**；`fix-fuzzy` 自动修了 3 条、留 49 条歧义。
- 线索：`/vol1/1000/runtime/fix-renamed-20260915-133644.log`、`fix-dbpaths-20260915-133704.log`、
  `fix-fuzzy-20260915-134352.log`，以及 `music_tables-backup-20260915-1346*.sql`。

### 2. 关键坑（务必记住）
- **`Track.path` 存的是容器内绝对路径** `/app/media/<相对路径>`；`path_hash = sha256(path)`（UTF-8，无盐）。
  **写库时忘了补 `/app/media` 前缀 → 16 行变成相对路径**，我在同一会话里踩了一次、又修回来了。
- **`path_hash` 有唯一索引**；`Album.full_text`、`Artist.full_text` 也有**唯一索引** ⇒ 新建专辑/艺术家必须先
  生成唯一 `full_text`（约定 `<name>` / `<name>-<n>`）。`Track` 没有 `path` 唯一索引 ⇒ 脏数据能写进去。
- `ImportMusic.diagnose_duplicate_paths()` 与 `fix_all_artist_issues()` 在 **MySQL 后端直接 `NotImplementedError`**
  （`user/applications` 是 Cython 编译的 `.so`，`inspect.getsource` 拿不到源码），只能自己写 ORM 脚本。
- **容器自带 ffmpeg 没有 chromaprint muxer**（`Unrecognized option 'fp_format'`），但 **NAS 宿主机 `/usr/bin/ffmpeg`
  8.1.1 有**。所以指纹要在宿主机跑：容器导出候选清单（`/app/media/...` → `/vol1/@team/public/music/...`）→
  宿主机 `ffmpeg -t 150 -f chromaprint -fp_format base64 -` 出指纹（~0.6s/首）→ 回容器执行删除。
- ffmpeg chromaprint 输出是 **URL-safe 无填充 base64**，标准 `b64decode` 会 `Incorrect padding`，必须
  `replace('-','+').replace('_','/')` 再补 `=`。
- **同名 ≠ 重复**：`loose(artist)+loose(title)` 分组后，只有 **指纹完全一致 + 时长差 ≤2s** 才算同一份录音。
  实测 337 个候选文件里，指纹完全一致 83 对，相似度 0.30~1.0 只有 1 对（0.73，低码率转码版，已清），
  其余同组文件相似度 <0.30 ⇒ 是不同录音（Live / 重编曲 / 不同年份版本），**必须保留**。
- 指纹只覆盖前 150s ⇒ **必须加时长校验**，否则「同前奏、不同长度」的两个版本会被误判成重复。
- 判「保留哪一份」的优先级：目录不是 `未知/未知专辑/unnamed` > 文件名无 `_时间戳` > 同目录文件数多 >
  无损/DSD 优先 > 码率高 > 体积大。
- music-tag-web 的 Subsonic 认证凭据在 **`user.UserProfile.subsonic_api_token`**（`auth.User` 上**没有**字段）。
  直接用 `u=<user>&p=<token>` 走 `http://127.0.0.1:8002/rest/…` 就能端到端验证。
- `settings.CACHES` 是 **DummyCache**（无 Django 缓存层），所以改库后 REST 立即生效；客户端那边要清
  `%APPDATA%\subsonic-player\cef-cache`。
- **`Archive/` 之外不要删 `music/attachments/`**；`music/.cache/`（100 文件/393MB）是 music-tag-web 的
  **转码缓存**，转码关掉后就是垃圾，可以直接删。

### 3. 复用脚本（工作区 `.audit/`，已 gitignore）
`dump-state.py`（导出 DB+磁盘+ffprobe 全量）→ `plan2.py`（多轮证据匹配）+ `build_apply.py` →
`fixdb.py`（改路径/删行/改名，带 JSON 备份）→ `importfree.py`（补导入 + 聚合刷新）→
`repair2.py`（专辑艺术家重绑/同名合并）→ `finalize.py`/`repair3.py` →
`dedup2a.py`+`dedup2b.py`+`sim2.py`（指纹去重）→ `apply_host.py`+`apply_container.py` →
`verify_final.py`（磁盘↔数据库双向核对）。**验收脚本 `verify_final.py` 是这套流程的收口，必须跑。**

## 2026-09-16 会话：曲目标题批量错乱的取证与修复

### 症状与根因
用户报「明天，你好这首歌不对板」。不是路径问题（那已在 09-15 修完），而是**标题被批量覆写**：
多个不同歌曲的文件被改成同一个名字，`title` 标签同时被写坏。典型 `牛奶咖啡/Lost & Found去寻找/`
三个文件全叫 `明天，你好`；`尹光` 下 8 个文件全叫 `剑合钗圆`（实为 8 首不同的歌）。

### 最有价值的取证手段（纯本地，不需要网络）
**文件内嵌 `lyrics` 标签里的 `[ti:...]`/`[ar:...]` 是「标题被覆写之前」抓歌词时留下的真实曲名。**
把它和「文件名 / 专辑目录名 / 同目录 `.lrc` 旁挂文件 / 官方时长」交叉验证，两个以上来源一致才动手：

- 规则 A：文件名 ≈ lyrics 标题，而 `title` 标签与两者都不符 ⇒ title 用文件名。
- 规则 B：`title` 标签 == 文件名（说明标签是从名字抄的），且 lyrics ≈ 专辑目录名或存在同名 `.lrc` ⇒ 用 lyrics。
- **反例（必须记住）**：`Taylor Swift - Paper Rings` 的 lyrics 写 `Paper Hearts`、
  `Imagine Dragons - Zero` 的 lyrics 写 `Imagine` —— 这两首**时长与标签吻合**，是 lyrics 错了。
  所以**绝不能只信 lyrics**，必须有第二个独立来源。
- 还有一类是**噪音 lyrics**（`[ti:June]`、`[ti:77243]`、`[ti:********]`、`[ti:Track 10]`、
  `[ti:y2002_dj...]`）—— 歌词源抓失败写进去的垃圾，要按正则滤掉，别当成真标题。

### 改写标签的正确姿势
- `ffmpeg -i in.flac -c copy -map_metadata 0 -metadata title=新名 out.flac`；m4a 追加
  `-movflags +faststart`（顺带解决服务端 `err=Unstreamable`）。
- **必须逐个校验音频流 MD5**：`ffmpeg -v error -i F -map 0:a -f md5 -`，改写前后对比，
  不一致就丢弃临时文件。本次 35/35 一致。
- `-c copy` 会保留其它标签（lyrics/composer/lyricist）—— 确认过没丢。
- 只改标签 + 数据库 `Track.name/name_pinyin/name_sort`，**不改文件名**（改名风险已被 09-15 那次
  事故证明，且客户端显示的是数据库名字，文件名不影响使用）。

### 其它
- 曲库里有**整族假文件**：`周深/借过一下/` 5 个文件的 `lyrics[ar]` 全是**陈小春**，
  即下载工具把一堆别的歌存成了同一个名字。改标题能治「显示不对」，但 artist/album 维度
  只能靠**按音频内容重新鉴定**（chromaprint → AcoustID，NAS 实测可达 api.acoustid.org）。
- music-tag-web 的 Subsonic token 在 `user.UserProfile.subsonic_api_token`，
  用 `u=<user>&p=<token>` 直接打 `http://127.0.0.1:8002/rest/…` 就能端到端验收（容器里没有 curl，用 urllib）。

## 2026-09-16 会话（续）：Chromaprint→AcoustID 全库重鉴定

用户要求「AcoustID key 在 music-manager 项目里，找出来然后把文件处理了，能改名的改名，不能改名的删了也行」。

### key 位置
`music-manager/app/acoustid.py` → `DEFAULT_CLIENT = "Z1SwTAHLhW"`（也在 `music-manager/MEMORY.md` 里记着）。
music-manager 的 AGENTS 声明其旧 Python 版已作废，但 key 仍可用。实测 NAS 能直连 `api.acoustid.org`。

### 工程要点
- **指纹必须在 NAS 宿主机算**：`/usr/bin/ffmpeg` 8.1.1 有 chromaprint muxer，而 music-tag-web 容器里的
  ffmpeg **没有**（`Unrecognized option 'fp_format'`）。
- **必须强制 IPv4**：`socket.getaddrinfo = lambda h,p,f=0,t=0,pr=0,fl=0: _orig(h,p,socket.AF_INET,t,pr,fl)`。
  这台 NAS 有 IPv6 地址但没有 IPv6 路由 —— 不强制就会每首等一次超时（music-manager 的 HANDOFF 也踩过）。
- **`meta` 参数**：用 `meta=recordings+releases` 时**必须手拼 body**，`urlencode` 会把 `+` 编成 `%2B`，
  AcoustID 就当成一个未知 meta 名，返回的 result 里没有 recordings。
- **存原始结果而不是解析结果**：`results.jsonl` 每行存 `{"path","dur","results":[...]}`（裁掉 releases 到 5 个），
  解析放到 diff 阶段做 —— 这样改了选优规则可以**离线重跑，不用重查**（重查一轮 40 分钟）。
- 限速：3 线程 + 全局 0.4s 间隔 ≈ 2.4 req/s，5980 首约 40 分钟；指纹 6 线程 `-t 150` ≈ 0.42s/首。
- 解析改标签用 `ffmpeg -c copy -map_metadata 0 -metadata title=...`（m4a 加 `-movflags +faststart`），
  **前后比对音频流 MD5**（用 `-t 60` 截前 60 秒即可，全片解码要两倍时间且无额外信息量）。
  `.dsf`/`.wav` 不要用 ffmpeg 写标签（不可靠），只改数据库即可。

### AcoustID 的四个陷阱（这是本次最重要的经验）
1. **一次查询返回该指纹的所有 release/credit，同一个 result 里会同时列出旋律相同但不同的歌**。
   实测把 `陈奕迅 - 明年今日.wav` 改成《十年》——因为《十年》(204.24s) 比《明年今日》(205.4s)
   更接近文件的 203s。**正解：先看有没有哪个候选的 title 与文件自己提供的标题候选一致，有就采信。**
2. **高分不等于对**。AcoustID 会把翻唱/其他 take 归到原唱：`One Direction - Story of My Life` →
   艺术家被改成 `Oliver Harrigan`、`白允y - 唯一` → `G.E.M. 邓紫棋`。所以**不要因为高分就凭空新建艺术家**；
   本会话的规则是「只复用库里已有的艺术家，只有该目录被判定为批量错下载目录时才允许新建」。
3. **相同 `recording_id` 不等于同一份音频**。1546 对同 recording_id 里，有 86 对是不同 take/母带。
   去重必须**再加 chromaprint 逐位比对**，只隔离 `similarity ≥ 0.99` 的（本次 1446 对）。
   ⚠️ 本项目的 `similarity` 是「同位哈希相等比例」：**1.0 是铁证，低值不是反证**
   （`夜机.flac` 与 `夜机(1).flac` 是同一录音但 sim=0.001）。
4. `recordings[0]` 顺序任意；时长选优**只在没有 title 命中时才用**（见第 1 条）。

### 副产品：怎么用数据认出「批量错下载目录」
目录名是**歌名**而不是歌手（`20岁的眼泪 (Live)/未知/`）—— 单文件目录靠「多数决」抓不到；
或该目录内多首在高分+时长吻合下指向**不同** AcoustID 艺术家、且**没有一个**等于目录名
（`AakI7zzz` 22 首几乎全是 Post Malone、`De_pres_sion` 全指向 Adele、`Ado` 是 One Direction+Wayne）。
本次共判定 1091 个此类目录，其下无法鉴定的 232 个文件被隔离。

### 教训
`tidy()` 那种「掐头去尾去掉标点」的小工具**不要把 `()` 也算进标点**——它把 `20岁的眼泪 (Live)`
变成 `20岁的眼泪 (Live`，导致括号候选永远匹配不上，标题里的 `(Live)` 被静默丢掉。这个 bug 我查了 5 轮。
**凡是「保留限定词」的逻辑，先写一条断言把 `foo (bar)` 原样打印出来看。**

## 2026-09-16 会话（再续）：删隔离区 + 清「目录名是歌名」的目录

用户：「隔离的删掉吧」「约 1091 个目录名是歌名而不是歌手…这些也能删的都删了吧。不知道是什么歌曲的话，对我来说是没有意义的。」

### 做了什么
1. **隔离区彻底删除**（不可回滚）：`.dsh-quarantine-20260915-212219/` 25GB/1941 文件 +
   `.dsh-trash-dup-<ts>/` 19MB + `.dsh-*-latest.txt` 标记文件。之后**任何破坏性操作都没有隔离网了**，
   再动手前必须先做备份。
2. **30 个批量错下载目录**（严格判据）清空：90 个文件按 AcoustID 重新归位到真实歌手，
   1 个 AcoustID 完全认不出的删除，29 个孤儿 `cover.jpg`/`.lrc` 删除。
3. **`<曲名>/未知/<曲名> - <歌手>` 型目录**：248 个文件按文件名重新归位，590 个只是文件名顺序反了、原地改名。
   顶层目录 903 → **656**。

### 最大的坑：`<A> - <B>` 的歧义
文件名 `<A> - <B>` 既可能是 `<歌手> - <曲名>`（本库正常形态），也可能是 `<曲名> - <歌手>`（错下载形态）。
**第一版**我按「哪一边是已登记的艺术家」判 —— 结果 `蔡琴/谈心/蔡琴 - 为何不早说.wav` 被认成
「蔡琴是曲名、为何不早说是歌手」，dry-run 给出 **3130 个**把正经歌手目录拆成曲名目录的移动。
**第二版**加 `a_is_artist → 跳过`，但因为**垃圾艺术家行本身就叫曲名**（库里存在名为 `回望`、`History` 的
artist 行），`a_is_artist` 对两边都是 True，直接失效（re-home 归零）。
**第三版（正解）**：用**目录形状**判断 ——
- 曲名侧：`<music>/<X>/<未知|未分类专辑>/`，即它的目录下**没有**正规专辑子目录；
- 歌手侧：`<music>/<Y>/<某正规专辑>/` 存在。
判据：`IKEY(top)==IKEY(a)` 且 `top` 的二级目录是 `未知/未分类专辑/unnamed` 且 `IKEY(b) ∈ REAL_DIRS`
且 `IKEY(a) ∉ REAL_DIRS`。

教训：**用「名字是否在表里」判断语义，遇到自指的表一定会失效**；要用**结构**（目录形状、时长、指纹）。

### 另一件事
`similarity`/「同名」这类字符串判据在本次全程都在坑我；能收敛的都是**结构性判据**
（时长是否吻合、目录形状、chromaprint 是否逐位相同、recording_id 是否相同）。
