# CURRENT_STATE.md — 接班说明（start here）

> 给下一个会话/代理的交接说明。详细项目记忆与坑见 `dotnet/MEMORY.md`（其中有「当前状态/待办」专节，含 10 个关键坑）。

## 这是什么

**Subsonic Player**（`github.com/lixscn/subsonic-player`，工作区 `D:\work_space\DeepSeekHarness\music-play`），.NET 10 + Avalonia 11.3.20 + CefGlue.Avalonia 120（Chromium 120, OSR 离屏渲染）。UI 为 HTML（`WebAssets/`，内嵌为 `AppUI.*` 程序集资源，经自定义 `app://` scheme 服务）。音频 BASS。

## 当前可运行状态（已达成）

- **单文件版**：`D:\tools\SubsonicPlayer-single\` → `SubsonicPlayer.exe`（~253MB，应用托管代码 + .NET 运行时）。CEF 原生 + `lib\bass*` **全外置**（CEF 不能打包进 exe）。
- 旧的非单文件版 `D:\tools\SubsonicPlayer` 已删除。
- **CI 绿**：Windows 构建产**单文件**（`publish-singlefile.ps1`）；Linux/macOS 仍非单文件。
- 构建脚本：`dotnet/publish-singlefile.ps1` / `.bat`（**必须 UTF-8 BOM**，否则 PowerShell 5.1 中文乱码→解析失败→CI 红）。

## 已实现（本轮）

- 黑屏排查定论：**不加任何 GPU 参数**（加了反而黑屏）。
- WebAssets **内嵌** + `ResourceHandler` **判空**（CEF 读完 EOF 还会再调 Read，不判空则 `ObjectDisposedException` 闪退）。
- 单文件 CEF 子进程依赖补齐（`publish-singlefile.ps1` 发布后拷贝完整 `CefGlueBrowserProcess`）+ locales 扁平化到根 `locales\`。
- BASS 在 `lib\` + `BassBootstrapper` 启动预加载（bass/bass_fx/bassmix）。
- 首播卡 UI 冻结修复：`CreateStream`(网络) 挪后台线程，回主线程经 `AppServices.UiDispatcher.Post`。
- exe 图标（`ApplicationIcon` = `Assets\avalonia-logo.ico`）。
- 网络慢优化：BASS 缓冲/超时、封面磁盘缓存+重试、列表 TTL 缓存、GetSongsPage 限并发、DownloadService 超时。
- CEF 加载方式（学 OutSystems WebView）：`app://` CustomScheme 设 `IsStandard/IsSecure/IsFetchEnabled`；`CefSettings` 深色背景 + 异常栈深；退出 `CefRuntime.Shutdown()`。
- 自定义强调色主题（5 套，设置→外观，localStorage 持久化）。
- localStorage 在 `app://` 下抛 `SecurityError` → 相关 JS 已 try/catch 防护。

## 待办（用户要求"都做"，尚未实现）

1. **完整内置预设主题**（**方案已定稿，待实现**）——当前只做了"强调色"（5 套，localStorage），未做完整主题。按用户确认：
   - 只做**内置预设主题**（不做外部自定义主题文件）。
   - 一个主题 = 一套完整 CSS 变量（背景/文字/边框/强调/玻璃等全部 `--*`）。
   - 预设（约 6 套，深浅就是其中两项）：深邃黑(dark,默认) / 月光白(light) / 森林绿 / 午夜蓝 / 落日橙 / 玫瑰紫。
   - **应用**：`app.js` 加 `applyTheme(id)` → `document.documentElement.style.setProperty(...)` 内联覆盖全套变量，即时生效。
   - **选择器**：设置弹窗加「主题」下拉列出 6 套；选即应用。
   - **深浅**：就是 dark/light 两套；顶栏 ☀/🌙 按钮保留作 dark/light 快捷切换。
   - **持久化**：选中主题 id 存 **C# `AppSettings`（加 `ThemeId` 字段）**，不依赖 localStorage（`app://` 下会 SecurityError）；启动/切换时 C# 经 `StateBridge.on('theme')` 把主题交给 JS 应用 → 下次打开恢复。
   - 实现：`styles.css`（把 `:root`/`html.light` 变量提为 dark/light 预设 + 新增 4 套）；`app.js`（主题字典 + applyTheme + 下拉联动 + 深浅按钮切 dark/light）；`index.html`（设置加主题下拉）；C# `AppSettings`（ThemeId + state 下发）。
2. **全屏播放器页**（HTML UI 新增沉浸式 Now Playing 视图）
3. **智能歌单编辑器（Navidrome）**（协议专属规则编辑）
4. **furigana/romaji 显示**

## 建议的下一步（在新会话继续）

- **先做「完整内置预设主题」**（方案已定稿、改动集中在 `WebAssets` + `AppSettings`，用户可感知度高），再做全屏播放器页。
- 改 `WebAssets`(HTML/CSS/JS) 后：`node --check app.js` → 清 `%APPDATA%\subsonic-player\cef-cache` → 重启（内嵌资源需重编译：`powershell -ExecutionPolicy Bypass -File dotnet/publish-singlefile.ps1`）。

## 关键目录

- `dotnet/src/SubsonicPlayer.Cef/WebAssets/` —— HTML 主界面（index.html / styles.css / app.js / mini.html）。
- `dotnet/src/SubsonicPlayer.Cef/Services/AppSchemeHandler.cs` —— `app://` 自定义协议 + 内嵌资源服务。
- `dotnet/src/SubsonicPlayer.Cef/Services/CefUiBridge.cs` / `CefPageDataProvider.cs` —— C#↔JS 桥、页面数据。
- `dotnet/src/SubsonicPlayer.Core/Services/` —— AudioEngine / PlaybackService / 音乐服务客户端 / 重试/缓存助手。
- `dotnet/publish-singlefile.ps1` —— 单文件发布脚本（含子进程依赖补齐 + locales 扁平化）。
