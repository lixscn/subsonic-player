# AGENTS.md

Subsonic Player — 面向 NAS 音乐服务的桌面播放器（Roon 风格 HTML UI）。详细设计见 `dotnet/PLAN.md`，项目记忆见 `dotnet/MEMORY.md`。

## 项目结构

- `dotnet/src/SubsonicPlayer.Core`：共享逻辑（BASS 音频、PlaybackService、AppServices、服务客户端、歌词）
- `dotnet/src/SubsonicPlayer.Cef`：**主力**，HTML UI（`WebAssets/`）+ CefGlue.Avalonia（OSR）
- `dotnet/publish.ps1`：全平台发布脚本

## 常用命令（在 `dotnet/` 目录执行）

```powershell
# 构建（Windows TFM）
dotnet build src/SubsonicPlayer.Cef/SubsonicPlayer.Cef.csproj -f net10.0-windows10.0.19041.0

# 校验 JS 语法（改 app.js 后必跑）
node --check src/SubsonicPlayer.Cef/WebAssets/app.js

# 运行（Debug 输出目录）
Start-Process "src/SubsonicPlayer.Cef/bin/Debug/net10.0-windows10.0.19041.0/SubsonicPlayer.exe"

# 停进程（改代码前先停）
Stop-Process -Name SubsonicPlayer -ErrorAction SilentlyContinue
Stop-Process -Name "Xilium.CefGlue.BrowserProcess" -ErrorAction SilentlyContinue

# 发布 Windows 程序包
powershell -ExecutionPolicy Bypass -File publish.ps1 -Platform win-x64
```

## 铁律（改这些必遵守）

1. **改了 WebAssets（HTML/CSS/JS）后必须清缓存再重启**，否则显示旧缓存：
   `Remove-Item "$env:APPDATA\subsonic-player\cef-cache" -Recurse -Force -ErrorAction SilentlyContinue`
2. **CefUiBridge 的 JS→C# 方法**：命名即 JS 调用名（`Bridge.invoke('setEqGain', ...)` 对应 `SetEqGain`），改方法名要同步 JS。
3. **列表/网格行 Grid 列宽用固定宽度**，禁止 `*` 自适应列（会导致右侧列错位）。
4. **OSR 键盘输入**依赖点击时 `_browser.Focus()`（`TryFocusBrowser`）——不要移除 PointerPressed 聚焦逻辑。
5. **侧面板关闭必须 `visibility:hidden`**（仅移出屏幕会残留 box-shadow 阴影）。
6. **EQ 频点必须 ≥80Hz**，带宽 12f（DX8 ParamEQ 限制），且与 JS `renderEq` 的 freqs 数组同步。
7. **双 TFM**：`net10.0-windows10.0.19041.0`（Windows）+ `net10.0`（跨平台）。跨平台代码用 `#if WINDOWS` / `OperatingSystem.IsXxx()`，Win32 P/Invoke 必须包 `#if WINDOWS`。
8. **配置/密码**：不硬编码服务器地址与凭据，不提交 settings.json；密码加密存储（Windows DPAPI / 其他 AES）。

## 跨平台注意

- BASS native 在 `src/SubsonicPlayer.Cef/native/{win-x64,osx,linux-x64}`，按 RID 自动拷（csproj `NativeDir`）。
- CEF native 由 NuGet redist 注入；产物结构差异：linux `libcef.so` 在 `CefGlueBrowserProcess\` 子目录、osx/win 在根。
- 新增 Windows 专属功能：SMTC/全局热键用 `#if WINDOWS`，非 Windows 走 Core 兜底。

## 调试

- **CEF 远程调试**：Program.cs 临时加 `--remote-debugging-port=9333` cmdArg，raw CDP 连 `http://127.0.0.1:9333/json` 的 page target（Playwright connectOverCDP 不兼容 CEF）。诊断后必须移除端口。
- **日志**：`%APPDATA%\subsonic-player\` 下 bridge.log / provider.log / eq.log / crash.log / cef.log。
- **物理键盘链路测试**：CDP focus input + `WScript.Shell.SendKeys`。

## 记忆边界

- 项目专属信息：`dotnet/MEMORY.md`（技术栈、坑、跨平台、调试）
- 跨项目通用信息：`~/.config/opencode/AGENTS.md`（全局）+ `~/memory/`
