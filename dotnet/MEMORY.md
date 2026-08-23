# subsonic-player 项目记忆

> 项目记忆。完整设计方案见 `PLAN.md`。

## 一句话

面向 NAS 音乐服务的**专业级桌面音乐播放器**（Roon 风格 UI）。当前自用服务端为「**道理鱼音乐**」（Gonic）。

## 技术栈（已定）

.NET 10 + Avalonia 11.3.20 + Semi.Avalonia 11.3.14 + CommunityToolkit.Mvvm + SQLite；音频引擎 BASS + BASS_FX；**UI 为 HTML（Chromium 120 via CefGlue.Avalonia 120.6099.211，OSR 渲染）**。

## 项目结构（dotnet/）

- `src/SubsonicPlayer.Core`：共享逻辑（BASS 音频引擎、PlaybackService、AppServices、Subsonic/Emby/Plex 客户端、歌词搜索）
- `src/SubsonicPlayer.Cef`：**主力**。HTML UI（WebAssets）+ CefGlue.Avalonia
- `src/SubsonicPlayer.Desktop`：Avalonia 原版 XAML UI，**已搁置**（不再维护 CEF 界面）
- `src/CefShadowTest`：空白 CEF 诊断项目（index.html 被破坏为测试状态，未提交 git）
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

1. **右侧"边框阴影"= queue-panel 的 box-shadow**：`position:fixed; right:0` 关闭时 `right:-340px` 移出屏幕，但 `box-shadow` 仍向左投射 → 右侧渐变阴影（深色不可见、浅色显形）。排查：CefShadowTest 空白页二分定位。**修复：关闭时 `visibility:hidden`（仅位移不够）**。教训：fixed/absolute + box-shadow 移出视口仍投影，须 visibility:hidden。

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

6. **图标用 SVG symbol sprite** 替代 unicode/emoji（▶⏮🔁 等在 CEF 缺字闪烁）。`<svg width=0><defs><symbol id="i-xxx">` + `<use href="#i-xxx">`。

7. **侧面板关闭残留阴影/不可见**：queue-panel 打开需同时 `right:0` + `visibility:visible`（`toggleSleepMenu` 曾漏 visibility 导致睡眠面板点不开）。统一用 `closeSidePanel()`，点击面板外空白也关闭（document mousedown 判断）。

8. **浅色主题下 accent-text 看不清**：`html.light` 需覆盖 accent 系列变量（`--accent-text` 浅色用深青绿 `#0B6B4F`）；`.dtab.active`（青绿底）文字用深墨绿 `#06231B` 而非浅青。

9. **配置页密码明文**：`GetServices` 不返回明文密码（只给 `hasPassword` 布尔），编辑时密码留空=不修改。

10. **播放列表水平滚动条**：`queue-item` 的标题/艺术家被塞进无类名的中间 `<div>`（grid 子项），该 div 默认 `min-width:auto` 会按 `nowrap` 文本撑宽 `1fr` 轨道 → 行宽超出 340px 面板出现横向滚动。**修复：`.queue-item > div { min-width:0; overflow:hidden; }`**，并给 `.content`/`.queue-body` 加 `overflow-x:hidden`。教训：grid 里作为子项的「文本包裹层」必须 `min-width:0`（对比 `song-row` 每列自带 overflow:hidden 所以不溢出）。

11. **双击播放 ×2**：播放动作绑在 `click` 上，而鼠标双击会触发两次 `click` → 同一首连续加载两次。**修复：`playOnce(key,fn)` 400ms 去重**（同一 key 窗口内只执行一次），包裹所有 `click` 播放入口（song-row / album 播放按钮 / 章节 play-all / 书签 / 队列项）。注意 Windows 系统双击判定默认上限 ~500ms，去重窗口取 400ms 才可靠。

## 发现页设计（重要）

- 顶部两个 **tab**：随机推荐 / 智能推荐，共用一个位置
- **每天只刷新一次**：`localStorage` 存 `discover_cache_{random|smart}` + 日期 key，跨天自动失效；「换一批」清当天缓存强制刷新
- **从上到下顺序加载**：tab → 最新专辑 → 常听专辑 → 高分专辑；未加载区块全部**骨架占位**（skel-rows/skel-albums/skel-detail，固定点位防跳动）
- 数据源：`getDiscoverQuick`（随机歌）、`getDiscoverMore`（智能推荐 + newest/frequent/highest 专辑，一次返回全部）
- 专辑/艺术家/歌曲/收藏/历史/书签/搜索页加载均用骨架占位（`skelGridHtml`/`skelSongListHtml`/`skelDetailHtml`）

## 其他功能要点

- **EQ 面板**：10 段滑块 + 预设；重开面板从 C# `GetEqGains()` 回显滑块与选中预设（匹配 presetGains 判定）
- **播放进度点**：updatePlayerBar 需同时更新 `fill.width` 和 `thumb.left`（曾只更新 fill 导致圆点不动）
- **队列封面占位**：无封面/加载失败用 `#i-music` SVG（span 需 `display:flex` 否则 grid 列塌陷错位）
- **书签行**：点击走 `playBookmark`（曾缺 data-song-id 无反应）
- **睡眠定时器**：C# DispatcherTimer 到点 Pause；JS `pickSleep` 高亮选中 + 提示"已设置 N 分钟"
- **多服务下拉**在顶栏（serviceSelect）；设置弹窗管理服务器增删改
- **服务端是 Gonic**：认证用 `p` 明文密码（不支持 token error 41）；`album.artist`/`year`/`track` 返回 Go 内存地址或 `<nil>` 脏数据；`getArtists` 含非法 XML 控制字符需清理；id 前缀 `alb_`/`trk_`/`art_`

## 跨平台（已就绪）

- **BASS**：`native/{win-x64,osx,linux-x64}` 三平台 9 库（bass + flac/opus/ape/wv/dsd/midi/fx/mix）；`LibraryExtension` 按平台 `.dll/.dylib/.so`；BassNative `Lib="bass"` 跨平台可用
- **CEF**：NuGet redist 按 RID 注入。注意发布产物结构差异：win `libcef.dll` 根目录、linux `libcef.so` 在 `CefGlueBrowserProcess\`、osx `libcef.dylib` 根目录
- **窗口拖动**：`#if WINDOWS` Win32（ReleaseCapture + WM_NCLBUTTONDOWN）；macOS/Linux 用 Avalonia `BeginMoveDrag`（OSR 缓存 `PointerPressed` 的 `PointerPressedEventArgs`）
- **托盘/窗口图标**：Windows 用 `.ico`，macOS/Linux 用 `.png`（`Assets/avalonia-logo.png`，平台判断资源名）
- **DPAPI 密码**：仅 Windows 注入（`OperatingSystem.IsWindows()`）；其他平台 AES-GCM
- **SMTC/全局热键**：`#if WINDOWS` 保护；非 Windows 走 Noop 兜底
- **发布**：`dotnet/publish.ps1 -Platform win-x64,linux-x64,osx-x64,osx-arm64`（self-contained 默认），自动校验 BASS/CEF/WebAssets

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

## 多服务支持

- 协议：Subsonic 族（Subsonic/Navidrome/Jellyfin/Gonic）+ Emby + Plex 已实现；AudioStation 规划中
- 扩展点：`MusicServiceFactory.Create` 按 `MusicServiceConfig.Type` 分支
- 曲库规模：约 2174 艺术家 / 28 个字母索引

## UI 布局规范（多次返工）

- 所有列表/网格行 Grid 列宽**必须固定宽度**，**禁止 `*` 自适应列宽**（标题伸缩导致右侧列错位，用户多次反馈）
- 容器窄（如 320 面板）时缩小固定列宽而非改用 `*`

## 来源项目

播放器服务的音乐库/Subsonic 服务托管在自建 NAS。
