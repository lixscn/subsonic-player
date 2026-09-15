# Subsonic Player

面向 NAS 音乐服务的**专业级桌面音乐播放器**，基于 .NET 与 Avalonia 构建，支持多种自托管音乐服务协议。

> 当前版本：v1.0.0（功能完善版）

## 功能特性

### 曲库浏览
- 艺术家 / 专辑 / 歌曲 三级浏览，封面墙展示
- 全局搜索（歌曲 / 专辑 / 艺术家，回车即搜）
- 发现页：最新添加 / 最高评分 / 最常播放 / 随机音乐
- **智能推荐**：「为你推荐」基于收藏偏好分析用户口味，推荐常听艺术家的未收藏曲目
- 最近播放、书签（续播）

### 播放
- HTTP 流式播放，队列管理，四种播放模式（顺序 / 随机 / 循环 / 单曲）
- Gapless 无缝播放 + 交叉淡入淡出（0–15s 可调）
- 播放位置记忆：本地 SQLite 持久化，重启后从上次进度恢复
- 系统媒体键 / 全局快捷键 / SMTC 任务栏媒体控制

### 歌词
- 服务端歌词（Subsonic `getLyrics` / Emby `Lyrics`）
- 网络兜底搜索（LRCLIB），同步歌词逐行滚动
- SQLite 离线缓存，二次秒开

### 音效
- 10 段图形均衡器 + 预设（摇滚 / 流行 / 古典 / 人声 / 重低音），支持导入/导出
- 实时频谱可视化
- DSP 效果：混响 / 回声 / 合唱 / 压缩器
- 回放速度 / 音调调整（BASS_FX tempo/pitch）

### 媒体管理
- 歌单 CRUD、歌曲「添加到歌单」
- 收藏（红心）、评分（1–5 星）
- 分享链接、下载原文件
- 播放历史 + scrobble 回传、播放队列云端同步

### 系统集成
- 系统托盘（单击/双击打开主窗口）、迷你播放器（与大窗口双向切换）
- 深色 / 浅色主题
- 睡眠定时器、网络质量设置（原始 / 高 / 中 / 低）

## 支持的音乐服务

| 服务 | 协议 | 状态 |
|------|------|------|
| Subsonic | 原生 Subsonic / OpenSubsonic | ✅ 已实现 |
| Navidrome | Subsonic 兼容 API | ✅ 可用 |
| Jellyfin | Subsonic 兼容模式 | ✅ 可用 |
| Gonic | Subsonic 兼容 API | ✅ 已适配 |
| Emby | Emby 原生 API | ✅ 已实现 |
| Plex | Plex API | ✅ 已实现 |
| AudioStation（群晖） | Synology DMS API | 🔜 规划中 |

> 自用服务端为「**道理鱼音乐**」（Gonic 实现）。其 API 存在若干非标准脏数据，客户端已做适配，详见 `MEMORY.md`。

## 技术栈与开源项目

本项目完全基于以下框架与开源项目构建：

### 运行时与 UI

| 项目 | 版本 | 用途 | 许可证 |
|------|------|------|--------|
| [.NET](https://dotnet.microsoft.com/) | 10 | 运行时 / 平台 | MIT |
| [Avalonia](https://avaloniaui.net/) | 12.0.3 | 跨平台 UI 框架 | MIT |
| [Semi.Avalonia](https://github.com/irihitech/Semi.Avalonia) | 12.0.3 | UI 主题 | MIT |
| [Avalonia.Themes.Fluent](https://www.nuget.org/packages/Avalonia.Themes.Fluent) | 12.0.3 | Fluent 主题 | MIT |
| [Avalonia.Fonts.Inter](https://www.nuget.org/packages/Avalonia.Fonts.Inter) | 12.0.3 | Inter 字体 | MIT |
| [AvaloniaUI.DiagnosticsSupport](https://www.nuget.org/packages/AvaloniaUI.DiagnosticsSupport) | 2.2.1 | 调试支持（仅 Debug） | MIT |

### 架构与数据

| 项目 | 版本 | 用途 | 许可证 |
|------|------|------|--------|
| [CommunityToolkit.Mvvm](https://github.com/CommunityToolkit/dotnet) | 8.4.1 | MVVM 框架（ObservableObject / RelayCommand） | MIT |
| [Microsoft.Data.Sqlite](https://learn.microsoft.com/dotnet/standard/data/sqlite/) | 10.0.9 | SQLite 数据访问（曲库缓存 / 历史 / 歌词 / 播放状态） | MIT |

### 音频引擎（原生库）

音频处理基于 [BASS](https://www.un4seen.com/) 音频库（Un4seen Developments）：

| 组件 | 用途 |
|------|------|
| BASS | 核心音频引擎（HTTP 流式播放） |
| BASS_FX | 音效处理（tempo / pitch / 环境 DSP） |
| BASSMIX | 混音器（Gapless + Crossfade 架构） |
| BASSFLAC | FLAC 解码 |

### 外部服务

| 服务 | 用途 |
|------|------|
| [LRCLIB](https://lrclib.net/) | 网络歌词搜索（服务端无歌词时的兜底来源） |

### 通信协议

- **Subsonic / OpenSubsonic API**：原生实现（`SubsonicClient`）
- **Emby API**：`EmbyMusicService`（JSON REST，兼容 Jellyfin 大部分端点）
- **Plex API**：`PlexMusicService`（XML，`X-Plex-Token` 认证）

## 架构

```
subsonic-player/
├── Models/            # 数据实体（Song / Album / Artist / Playlist / Lyrics / Share / Bookmark …）
├── Services/
│   ├── SubsonicClient        # Subsonic API 客户端（XML 解析）
│   ├── EmbyMusicService      # Emby 原生协议
│   ├── PlexMusicService      # Plex 原生协议
│   ├── MusicServiceBase      # 非 Subsonic 协议公共基类
│   ├── MusicServiceFactory   # 按配置创建 IMusicService
│   ├── AudioEngine           # BASS + BASS_FX 封装（Mixer 架构）
│   ├── PlaybackService       # 队列 / Gapless / Crossfade / EQ / DSP
│   ├── RecommendationService # 智能推荐算法
│   ├── LyricsSearchService   # LRCLIB 网络歌词
│   ├── DownloadService       # 原文件下载
│   ├── LibraryDatabase       # SQLite 缓存 / 历史 / 歌词 / 播放状态
│   └── …
├── ViewModels/        # MVVM 视图模型
└── Views/             # Avalonia XAML 视图
```

所有音乐服务统一实现 `IMusicService` 接口，UI 层不感知具体协议。

## 构建

要求 .NET 10 SDK。

```bash
# 开发构建
dotnet build subsonic-player/SubsonicPlayer.csproj

# 发布（自包含，无需安装 .NET，输出到 publish 目录）
dotnet publish subsonic-player/SubsonicPlayer.csproj -c Release -r win-x64 --self-contained true
```

## 下载

发布包见 [Releases](https://github.com/lixscn/subsonic-player/releases) 页，解压后运行 `SubsonicPlayer.exe` 即可（无需安装 .NET 运行时）。

## 服务器连接配置

服务器地址、用户名、密码**不硬编码**，也不提交到仓库。首次启动后，在「设置 → 音乐服务」页填写即可，配置持久化到本机数据目录（`%APPDATA%\subsonic-player\settings.json`，已被 `.gitignore` 忽略）。

认证方式：
- Subsonic / Gonic：`p` 参数（明文密码）
- Emby：用户名密码（`AuthenticateByName`）或 API Key
- Plex：`X-Plex-Token`（填入「API Key」字段）

## 项目文档

- `PLAN.md` — 设计方案与里程碑（P1~P5）
- `MEMORY.md` — 当前状态与设计决策

## 许可

本项目代码开源。所依赖的第三方库均遵循各自的开源许可证（详见「技术栈与开源项目」表）。音频引擎 BASS 系列为 Un4seen Developments 产品，非商业使用免费，商用需另行授权。
