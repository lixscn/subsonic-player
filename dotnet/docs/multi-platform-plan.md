# 多平台支持方案（P5 里程碑）

> 目标：在保留现有 Windows 能力的前提下，让应用可在 **Windows / macOS / Linux** 编译、运行与发布。
> 本文件是 P5 的详细实施规划；决策项需与 PLAN.md P5 保持一致，实施前逐项确认。

## 0. 现状盘点（Windows 专属耦合点）

| 耦合点 | 位置 | 当前实现 | 平台依赖 |
|--------|------|----------|----------|
| TFM | `SubsonicPlayer.csproj` | `net10.0-windows10.0.19041.0` | WinRT 投影（仅 Windows TFM 提供） |
| OutputType | `SubsonicPlayer.csproj` | `WinExe` | Windows 概念 |
| app.manifest | `SubsonicPlayer.csproj` | 引用 `app.manifest`（DPI 感知） | Windows |
| 音频引擎 | `native/*.dll` | BASS + bassmix + bass_fx + 6 个解码插件（全部 win-x64） | Windows x64 二进制 |
| BASS P/Invoke | `BassNative.cs` 等 | `DllImport("bass")` | 库名解析跨平台兼容（见 §2.2） |
| SMTC | `SmtcService.cs` | WinRT COM（`Windows.Media` 投影类型） | Windows 专有 |
| 全局快捷键 | `GlobalHotkeyManager.cs` | `user32.dll RegisterHotKey` + `Win32Properties` WndProc 钩子 | Windows 专有 |
| 密码加密 | `PasswordProtector.cs` | DPAPI `crypt32.dll` | Windows 专有 |
| 数据目录 | `AppServices.cs` 等 | `Environment.SpecialFolder.ApplicationData` | .NET 已自动按平台映射（Win `%APPDATA%` / Linux `~/.config` / mac `~/Library/Application Support`），**无需改代码** |
| 下载目录 | `DownloadService.cs` | `SpecialFolder.MyMusic` | 同上，自动映射 |
| 托盘图标 | `App.axaml.cs` | Avalonia `TrayIcon` | Avalonia 跨平台（Win32 / mac NSStatusItem / Linux AppIndicator） |
| SQLite | `Microsoft.Data.Sqlite` 10.0.9 | NuGet 原生 `e_sqlite3` | 按 RID 自动提供原生库，跨平台 |
| 迷你播放器 | `MiniPlayerView` | 纯 Avalonia | 跨平台 |

结论：业务/UI/网络/数据层已跨平台；**需要处理的只有 TFM、BASS 原生库、SMTC、热键、密码加密、发布产物** 六块。

## 1. TFM 解耦（P5.0，先决条件）

### 决策：多目标 + `#if WINDOWS` 条件编译

```
<TargetFrameworks>net10.0;net10.0-windows10.0.19041.0</TargetFrameworks>
```

- **Windows 构建**用 `net10.0-windows10.0.19041.0`：自动定义 `WINDOWS` 符号，SMTC 的 WinRT 投影类型可用。
- **macOS / Linux 构建**用 `net10.0`：不含 WinRT 投影，`#if WINDOWS` 内的代码不编译。
- 不用「单 TFM + 手动符号」方案：SMTC 依赖 WinRT 投影类型，非 Windows TFM 下无法编译，多目标是唯一干净路径。

### csproj 调整

```xml
<TargetFrameworks>net10.0;net10.0-windows10.0.19041.0</TargetFrameworks>
<OutputType Condition="'$(TargetFramework)' == 'net10.0-windows10.0.19041.0'">WinExe</OutputType>
<OutputType Condition="'$(TargetFramework)' == 'net10.0'">Exe</OutputType>
<ApplicationManifest Condition="'$(TargetFramework)' == 'net10.0-windows10.0.19041.0'">app.manifest</ApplicationManifest>
```

### 代码隔离清单

| 文件 | 处理 |
|------|------|
| `SmtcService.cs` | 整个文件包 `#if WINDOWS`，并实现共享接口 `IMediaIntegration`（见 §4） |
| `GlobalHotkeyManager.cs` | 整个文件包 `#if WINDOWS`；非 Windows 用应用内快捷键兜底（见 §4） |
| `PasswordProtector.cs` | DPAPI 部分包 `#if WINDOWS`；非 Windows 用 AES-GCM 实现（见 §3） |
| `App.axaml.cs` | SMTC 初始化分支：仅 `#if WINDOWS` 调用；接口化后按平台解析实现 |
| `AppServices.cs` | `Smtc` 静态属性改为 `IMediaIntegration` 工厂，按 `OperatingSystem.IsWindows()` 选择实现 |

验收：`dotnet build -f net10.0` 在任意平台 0 错误；`-f net10.0-windows10.0.19041.0` 在 Windows 0 错误。

## 2. 音频引擎跨平台（P5.1）

### 2.1 前提：BASS 全平台官方支持（已验证）

un4seen 对每个插件提供 Win32 / macOS / Linux / Android / iOS 构建，且**命名规范统一**：

| 组件 | Windows | macOS | Linux |
|------|---------|-------|-------|
| BASS | `bass.dll` | `libbass.dylib` | `libbass.so` |
| BASSmix | `bassmix.dll` | `libbassmix.dylib` | `libbassmix.so` |
| BASS_FX（第三方） | `bass_fx.dll` | `libbass_fx.dylib` | `libbass_fx.so` |
| BASSFLAC / OPUS / APE / WV / DSD / MIDI | `bassxxx.dll` | `libbassxxx.dylib` | `libbassxxx.so` |

官方下载页（已验证链接格式）：
- 核心：`https://www.un4seen.com/files/bass24-osx.zip` / `bass24-linux.zip`
- 插件：`files/bassmix24-osx.zip`、`files/bassmix24-linux.zip`、`files/bassflac24-osx.zip`（同规pattern），`files/z/0/bass_fx24-osx.zip`、`files/z/0/bass_fx24-linux.zip`
- 插件 mac/linux 全部有对应包：mix、flac、opus、ape、wv、dsd、midi、fx

### 2.2 P/Invoke 库名解析（无需改代码）

.NET 在 Linux/macOS 对 `DllImport("bass")` 自动探测 `libbass.so` / `libbass.dylib`（加 `lib` 前缀 + 平台后缀）。现有 `Lib = "bass"` 常量**三个平台通用**，`BASS_Init(-1,…)` 选择默认输出设备同样跨平台。

### 2.3 原生库目录结构 + csproj 条件拷贝

```
native/
  win-x64/    bass.dll bassmix.dll bass_fx.dll bassflac.dll bassopus.dll bassape.dll basswv.dll bassdsd.dll bassmidi.dll
  osx-x64/    libbass.dylib libbassmix.dylib …（同 9 个）
  osx-arm64/  …（Apple Silicon）
  linux-x64/  libbass.so libbassmix.so …（同 9 个）
  linux-arm64/ …
```

csproj 按 `$(RuntimeIdentifier)` 条件引入，避免跨平台文件名冲突（同名 .dll/.so/.dylib）：

```xml
<PropertyGroup>
  <NativeDir Condition="$(RuntimeIdentifier.StartsWith('win'))">native\win-x64</NativeDir>
  <NativeDir Condition="$(RuntimeIdentifier.StartsWith('osx'))">native\osx-$(RuntimeIdentifier.Substring(4))</NativeDir>
  <NativeDir Condition="$(RuntimeIdentifier.StartsWith('linux'))">native\linux-$(RuntimeIdentifier.Substring(6))</NativeDir>
  <NativeDir Condition="'$(NativeDir)' == ''">native\win-x64</NativeDir>  <!-- 本地开发默认 -->
</PropertyGroup>
<ItemGroup Condition="'$(NativeDir)' != ''">
  <Content Include="$(NativeDir)\*" CopyToOutputDirectory="PreserveNewest" />
</ItemGroup>
```

### 2.4 平台注意事项

- **Linux**：Avalonia 依赖 GTK3（`libgtk-3-0`）；BASS Linux 版依赖 ALSA（`libasound2`）。发布说明中列出。
- **macOS**：BASS 走 CoreAudio，无需额外依赖；首次运行 Gatekeeper 提示需处理（见 §5）。
- **MIDI 特殊**：bassmidi 播放需要 SF2 音色库，Windows 下目前也只加载了 dll（无 SF2 则 MIDI 无声）。多平台阶段顺带决策：内置一个小型 GM SF2 或标注 MIDI 需音色库。
- **DSD**：所有平台均经 mixer 转 PCM 播放，行为与 Windows 一致。

验收：各平台 `BASS_Init` 成功、`BASS_PluginLoad` 至少 FLAC/Opus 插件加载成功、播同一 URL 无报错。

## 3. 密码加密跨平台（P5.2）

### 决策：接口化 `ISecretProtector`，Windows 保留 DPAPI，macOS/Linux 用 AES-GCM

```
ISecretProtector { string Protect(string); string Unprotect(string); }
├── DpapiSecretProtector   # #if WINDOWS，现有 crypt32 实现原样搬入
└── AesSecretProtector     # #else，AES-256-GCM，密钥存用户数据目录 secret.key（0600）
```

- `PasswordProtector` 改为按 `OperatingSystem.IsWindows()` 解析实现；`EncryptedPasswordConverter` 逻辑不变。
- AES 密钥生成：`RandomNumberGenerator` 生成 32 字节，首次运行落盘到数据目录（与 settings.json 同目录，文件权限 0600）。
- **安全等级说明**：DPAPI 密钥由当前用户凭据托管（无自管密钥）；AES-GCM 密钥存在磁盘上，安全性低于 Keychain/libsecret，但**无额外依赖、可移植、够用于个人 NAS 场景**。
- **可选增强（不做进 P5，记入 todo）**：macOS 用 `Security.framework` P/Invoke（`SecItemAdd`/`SecItemCopyMatching`）存 Keychain；Linux 用 libsecret DBus。若用户后续要求更高安全等级再升级，接口已预留。

验收：Windows 下旧 DPAPI 密文仍可解密（无感迁移）；macOS/Linux 首次写入密码 → 重启后自动解密成功。

## 4. 托盘 / 快捷键 / 媒体集成分支（P5.3）

### 4.1 托盘 — 无需改动

Avalonia `TrayIcon` 三平台原生支持（Windows 通知区 / macOS 菜单栏 / Linux AppIndicator·StatusNotifier）。现有 `SetupTray` 直接可用。

### 4.2 媒体集成（SMTC → 抽象接口）

```csharp
public interface IMediaIntegration : IDisposable
{
    bool IsAvailable { get; }
    void Initialize(Window? window);
    void UpdateTrack(string title, string artist);
    void UpdatePlaybackStatus(bool playing);
    void UpdateCover(IImage? image);
}
```

| 平台 | 实现 | 说明 |
|------|------|------|
| Windows | `SmtcService`（改造为接口实现，逻辑不变） | 现有任务栏媒体控制 |
| Linux | `MprisService`（新写） | MPRIS DBus 协议 `org.mpris.MediaPlayer2`，接入 GNOME/KDE 媒体控制；用 `Tmds.DBus` NuGet（跨平台托管 DBus 客户端） |
| macOS | `MacNowPlayingService`（可选） | `MPNowPlayingInfoCenter`（MediaPlayer.framework，macOS 10.12.2+）P/Invoke 暴露曲目/状态/封面，接入控制中心「正在播放」；若 P5 时间紧可先 no-op，记入 todo |

`AppServices.Smtc` 改为 `MediaIntegration` 工厂：按 `OperatingSystem` 返回对应实现（Windows 分支保持 `SmtcEnabled` 开关）。`PlaybackService` 各处调用改为接口方法，签名不变。

### 4.3 全局快捷键

- **Windows**：保留现有 `user32 RegisterHotKey`。
- **macOS / Linux**：**P5 降级为「应用内快捷键」**（窗口聚焦时通过 Avalonia 按键事件响应 播放/暂停/上/下，等同现有命令映射），并**隐藏「全局」文案、注明仅应用内生效**。设置项保留但行为按平台不同。
- 原因：Linux Wayland 从根本上限制全局热键（需 XGrabKey / 桌面 Portal）；macOS 需 Carbon `RegisterEventHotKey` 或 CGEventTap（辅助功能授权）。均为平台大工程，不阻塞 P5 主目标。
- **可选增强（记入 todo）**：Linux X11 `XGrabKey`；macOS Carbon `RegisterEventHotKey`（需辅助功能权限）。

验收：三平台启动无异常；Windows 热键全局生效；macOS/Linux 应用内快捷键生效。

## 5. 发布产物（P5.4）

### 发布命令

```bash
# Windows（现状保留）
dotnet publish -c Release -f net10.0-windows10.0.19041.0 -r win-x64 --self-contained

# macOS（Apple Silicon / Intel）
dotnet publish -c Release -f net10.0 -r osx-arm64 --self-contained
dotnet publish -c Release -f net10.0 -r osx-x64 --self-contained

# Linux
dotnet publish -c Release -f net10.0 -r linux-x64 --self-contained
dotnet publish -c Release -f net10.0 -r linux-arm64 --self-contained
```

### 平台打包差异

| 平台 | 产物 | 说明 |
|------|------|------|
| Windows | `.zip`（现有流程） | 无变化 |
| macOS | `.app` bundle（`Info.plist` + 图标 + 可执行）+ 可选 `.dmg` | 需要 `Info.plist`（含 `CFBundleIdentifier`/`CFBundleIconFile`/`NSHighResolutionCapable`）；个人使用可 ad-hoc 签名跳过公证，分发需 Apple Developer 账号 |
| Linux | `.tar.gz` + 可选 AppImage | Avalonia 依赖 `libgtk-3-0`、`libasound2`；发布说明列出 |

### 版本策略

- 与现有 tag `v1.0.0` 风格一致，`win-x64 / osx-arm64 / osx-x64 / linux-x64` 命名 `SubsonicPlayer-<rid>.zip`。
- 首次跨平台发布为独立里程碑，不并入 Windows 日常迭代。

## 6. 实施顺序与任务拆分

| 阶段 | 任务 | 交付 |
|------|------|------|
| P5.0 | TFM 多目标 + `#if WINDOWS` 隔离（SMTC/热键/DPAPI/manifest/OutputType） | 三平台可编译 |
| P5.1 | 下载 BASS mac/linux 9 个库 + csproj RID 条件拷贝 + 平台验证 | 三平台可播放 |
| P5.2 | `ISecretProtector` + AES-GCM 实现 + 工厂切换 | 密码跨平台持久化 |
| P5.3 | `IMediaIntegration` 接口 + Linux MPRIS + 热键降级 | 系统集成不崩溃 |
| P5.4 | publish 脚本 + mac .app 打包 + 发布说明 | 三平台可分发 |

## 7. 风险与注意点

1. **Wayland 全局热键不可用**（协议限制）→ 已降级为应用内快捷键，避免承诺。
2. **macOS 媒体集成 / 密码 Keychain** 涉及私有或需授权的 API → P5 用 no-op / AES 兜底，避免卡里程碑。
3. **MIDI 无音色库**：bassmidi 加载成功 ≠ 能出声，需 SF2。跨平台阶段一并决策。
4. **Linux GTK/ALSA 依赖**：发布说明必须列系统包，否则「无法启动/无声音」会误报为程序 bug。
5. **测试矩阵**：BASS 各平台解码行为一致（opus/ape/wv/dsd 插件三平台均有官方包），但需各平台实播验证，尤其 DSD→PCM 路径。

---

# 移动端（Android / iOS）专项 —— P6 独立里程碑

> 结论先行：**技术上可行，但工程量和 UI 改造远大于桌面三平台，建议作为 P6 独立里程碑，不在 P5 内承担。**
> 关键事实（已核实）：Avalonia 12 官方支持 Android/iOS（2026-04 发布，Android 后端整体重写、iOS 走 scenes 生命周期、支持 Mac Catalyst、NativeAOT 4 倍启动提速）；BASS 官方提供 Android（arm64-v8a/armeabi-v7a/x86_64）+ iOS（xcframework）构建，插件同样齐全。**同一套 XAML/C# 可覆盖手机。**

## M0. 现状与可行性

| 项 | 结论 |
|----|------|
| UI 框架 | Avalonia 12 手机端「生产可用」：Android 后端重写、原生调度器、touch 导航模式（抽屉/底部 Tab/底部弹层/手势），非 Win32/桌面 API 全隔离 |
| 音频引擎 | BASS 官方 Android（AAudio/OpenSL ES/AudioTrack 输出）+ iOS（CoreAudio，2.4.17 起插件为动态库 xcframework，`BASS_PluginLoad` 按文件名加载，与桌面同 API）——**现有 `BassNative` P/Invoke 直接复用** |
| 系统媒体集成 | Android `MediaSession`（通知栏/锁屏媒体控制）；iOS `MPNowPlayingInfoCenter` + Remote Command Center —— 对应桌面 SMTC 的移动形态 |
| 后台播放 | 需平台专项：Android 前台服务（`MediaSessionService`）+ iOS 后台音频模式（Info.plist `UIBackgroundModes=audio`） |
| 密码加密 | Android Keystore / iOS Keychain（替换 AES 兜底，接口已预留） |
| 发布 | Android：APK/AAB（侧载免签名）；iOS：**需 Apple 开发者账号（$99/年）+ 证书/描述文件/TestFlight 或 App Store** —— 个人分发最大摩擦点 |
| 托盘/全局热键 | 手机无此概念，直接不参与 |

## M1. 工程结构改造（前置，必做）

**手机端无法与桌面端共用同一个输出项目**（Android 需 manifest/Activity，iOS 需 Info.plist/AppDelegate/scene）。必须拆分三层：

```
SubsonicPlayer.sln
├── src/SubsonicPlayer.Core/          # net10.0  纯共享库
│   ├── Models / Services（SubsonicClient、AudioEngine、Playback、Library、
│   │   Settings、Favorites、Lyrics…）/ ViewModels
├── src/SubsonicPlayer.Desktop/       # 现有工程改造：net10.0-windows10.0.19041.0 + net10.0
│   ├── Views / App.axaml / MainWindow / Tray / SMTC / 全局热键（#if WINDOWS）
└── src/SubsonicPlayer.Mobile/        # net10.0-android + net10.0-ios（新工程）
    ├── 移动端 App.axaml + 页面（引用 Core 的 ViewModels）
    ├── Android：MainActivity / manifest / MediaSessionService
    └── iOS：AppDelegate / scenes / MPNowPlayingInfoCenter 集成
```

- **共享边界**：ViewModels 和 Services 全部下沉 Core（它们不含平台 API）；Views 因桌面布局（三栏+底部栏）与手机（抽屉+Tab）差异大，**各自实现，不共享**。
- 这是一次**破坏性重构**（现有单工程拆三），建议在 P5 桌面完成、且 git 分支切好后单独进行，避免与 P5 并行冲突。

## M2. 移动端 UI 改造（工作量最大项）

- 桌面「左侧导航 + 主内容 + 底部播放栏」→ 手机「**底部 Tab**（发现/曲库/正在播放/设置）+ 抽屉导航 + 底部弹出歌词/队列 + 手势返回」。
- Avalonia 12 自带 touch 导航控件（`TabBar`、`NavigationView`、`BottomSheet`），但**现有 View 均按桌面宽度与固定列宽设计，需逐页重排**（MEMORY.md 固定列宽规范在窄屏上不成立，需按移动尺寸重设计）。
- 推荐做法：复用 `ViewModels`（绑定逻辑不动），重写 `Views`。

## M3. 移动专项工程点

| 项 | 平台 | 说明 |
|----|------|------|
| 后台播放 | Android | 前台服务 + `MediaSession` + 通知栏控制；系统会杀后台时保持 |
| 后台播放 | iOS | `AVAudioSession` 后台模式 + `MPRemoteCommandCenter`；需 Info.plist 声明 `audio` 后台模式 |
| 媒体集成 | Android | `MediaSessionCompat` 暴露曲目/封面/播放状态/媒体键（等价 SMTC） |
| 媒体集成 | iOS | `MPNowPlayingInfoCenter` 暴露曲目/封面/进度；Remote Command 处理播放/暂停/上/下 |
| 密码 | Android | `AndroidKeyStore`（Keystore 系统）加密 AES 密钥 |
| 密码 | iOS | Keychain（`SecItem*` P/Invoke） |
| 音频会话 | iOS | 初始化时配置 `AVAudioSession` Category=Playback（否则静音开关下无声） |
| 原生库 | Android | `native/arm64-v8a+armeabi-v7a+x86_64` 的 `libbass*.so`，csproj 条件拷贝 |
| 原生库 | iOS | BASS xcframework 链接进 app；插件 `BASS_PluginLoad("bassflac")` 按名加载 |
| 网络 | 通用 | 现有 `SubsonicClient`（HTTP+XML）手机端直接可用；WAN 地址优先于 LAN（现有逻辑已支持） |

## M4. 决策建议（供用户选择）

- **方案 A（推荐当前）**：P6 立项，先拆 `Core` 层 + 桌面回归验证（1 次重构成本），移动端 UI 随后逐页做。手机端最终交付。
- **方案 B（暂缓）**：手机端先用成熟第三方客户端（Symfonium / substreamer / play:Sub——PLAN.md 兼容客户端列表已收录），桌面 P5 完成后视需求再决定是否立项 P6。**注意：不拆 Core 层，未来立项移动端时重构成本会更高。**
- **方案 C（不立项）**：项目定位桌面专业播放器，移动端完全交给生态客户端。成本最低，放弃手机形态。

> 若要为 P6 保留可能性，**至少做「拆 Core 层」**——它是唯一不可逆成本递增的决策点；拆分本身不改变桌面行为，可在 P5 间隙低风险完成。