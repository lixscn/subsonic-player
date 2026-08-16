# Subsonic 专业音乐播放器 — 设计方案

> 建立时间：2026-08-15。本文件保存了与用户的方案讨论与最终决策，供新项目实现时直接参考。

## 项目目标

为自建 Subsonic 服务开发一个**专业级桌面音乐播放器**，具备完整音效（EQ/DSP/可视化）、无缝播放、交叉淡入淡出、曲库管理、歌词等功能。

## 已确定的决策

| 决策项 | 结论 |
|--------|------|
| 平台 | Windows 桌面应用 |
| 框架 | .NET 10 |
| UI | Avalonia 12.0.3 + Semi.Avalonia |
| 模式 | MVVM（CommunityToolkit.Mvvm） |
| 存储 | SQLite |
| 连接配置 | 服务器地址 / 用户名 / 密码 **可配置**（持久化到 settings，支持多服务器切换，不硬编码） |
| 音频引擎 | BASS + BASS_FX |

## Subsonic 服务器连接信息

| 项 | 值 |
|----|----|
| 服务器（局域网） | 内网地址（由用户配置，不硬编码） |
| 服务器（公网） | 公网地址（由用户配置，不硬编码） |
| 用户名 / 密码 | 由用户在设置页填写，持久化到本机，不提交到仓库 |
| 协议 | Subsonic / OpenSubsonic 标准 API |

已验证：`rest/ping` → `status="ok"`；`getMusicFolders` → `Music`；`getArtists` 正常。
播放流：`/rest/stream`（HTTP 渐进式流）、`/rest/download`；服务端用 jellyfin-ffmpeg 转码。

## 架构分层

```
subsonic-player/
├── Models/          # 数据实体
├── Services/
│   ├── SubsonicClient    # HTTP API 客户端（XML 解析，salt+MD5 认证）
│   ├── AudioEngine       # BASS + BASS_FX P/Invoke 封装
│   ├── PlaybackService   # 队列/播放模式/gapless/crossfade
│   ├── LibraryService    # SQLite 曲库缓存
│   ├── PlaylistService   # 播放列表
│   └── SettingsService   # 配置持久化（含服务器地址/用户名/密码，明文或加密存储，可多服务器）
├── ViewModels/
└── Views/
```

## 音频引擎设计（音效核心）

- **播放架构**：BASS Mixer + DECODE 流 → 支持 Gapless + Crossfade（两路流音量斜坡交叉）
- **图形 EQ**：10 段（31Hz–16kHz），`BASS_DX8_PARAMEQ` 链式，带预设（摇滚/流行/古典/人声/重低音/自定义）
- **交叉淡入淡出**：可调 0–15s
- **无缝播放**：mixer 预加载下一曲，专辑内零间断
- **ReplayGain**：读 FLAC/Vorbis 标签（track gain）+ preamp 偏移
- **环境 DSP**：混响、回声、合唱、立体声扩展、压缩器（BASS_FX 内置）
- **频谱可视化**：`BASS_ChannelGetData` FFT（512/1024/2048）→ Avalonia 实时绘制
- **回放速度/音调**：BASS_FX tempo

## 功能清单

**播放**
- 队列管理（拖拽排序/清空/移除）、四种播放模式（顺序/随机/循环/单曲）
- 音量 + 左右平衡、回放速度/音调
- 进度条精确 seek、上一首/下一首
- 无缝播放（Gapless）+ 交叉淡入淡出（Crossfade）
- 后台播放 + Windows SMTC（任务栏缩略图、媒体键）

**曲库浏览**
- 艺术家/专辑/歌曲三级浏览、专辑封面
- 艺术家详情（简介/相似歌手 `getArtistInfo2`）、专辑详情（`getAlbumInfo2`）、Top Songs、相似歌曲
- 按流派浏览（`getGenres` + `getSongsByGenre`）、按年代
- 搜索（`search3`）

**发现**
- 多类型列表（`getAlbumList2`）：最新/最高评分/最常播放/最近添加/随机/按年代/按流派
- 随机音乐（`getRandomSongs`）、正在播放（`getNowPlaying`）

**音效**
- 10 段 EQ + 预设、DSP（混响/回声/合唱/立体声扩展/压缩器）
- ReplayGain、频谱可视化

**播放列表 / 收藏**
- 服务端歌单读取（`getPlaylists`/`getPlaylist`）+ CRUD、智能列表（最近添加/收藏/高评分）
- 播放队列云端同步（`savePlayQueue`/`getPlayQueue`）
- 星标（`star`/`unstar`）、评分（`setRating`）、收藏（`getStarred2`）

**歌词**：滚动歌词（`getLyrics` / `getLyricsBySongId`，时间戳同步）

**历史 / 缓存**
- 播放历史 + scrobble 回传；书签（记住播放位置）
- SQLite 缓存元数据 + 封面秒开

**电台 / 下载 / 分享**
- 互联网电台浏览/播放（`getInternetRadioStations`）
- 下载原文件（`download`）
- 分享链接（`getShares`/`createShare`）

**专业增强**
- 系统托盘 + 迷你播放条 + 全局快捷键
- 深色/浅色主题
- 睡眠定时器
- EQ 预设导入/导出
- 流式缓冲设置（网络质量）
- 服务器连接设置（地址/用户名/密码，可添加多个服务器并切换）

## Subsonic API 端点（完整能力）

> 原则：**充分使用服务端接口**，能读的服务端数据一律读下来用（歌单、收藏、评分、历史、电台、书签、分享等），本地仅做缓存与增强。认证：服务端为 Gonic，使用 **`p` 参数（明文密码）**，不支持 token 认证；请求带 `u / p / v / c / f` 参数。标注「OS」为 OpenSubsonic 扩展，需探测 `getOpenSubsonicExtensions`。

### 系统
| 端点 | 用途 | 阶段 |
|------|------|------|
| `ping` | 连接检测 | P1 |
| `getOpenSubsonicExtensions` | 探测服务端扩展能力 | P1 |

### 曲库浏览
| 端点 | 用途 | 阶段 |
|------|------|------|
| `getMusicFolders` | 音乐目录 | P1 |
| `getArtists` / `getIndexes` | 艺术家列表 | P1 |
| `getArtist` | 艺术家专辑 | P1 |
| `getAlbum` | 专辑歌曲 | P1 |
| `getSong` | 单曲详情 | P1 |
| `getArtistInfo2` (OS) | 艺术家简介/图片/相似歌手 | P1 |
| `getAlbumInfo2` (OS) | 专辑介绍/备注 | P1 |
| `getTopSongs` | 歌手热门歌曲 | P1 |
| `getSimilarSongs2` (OS) | 相似歌曲 | P1 |
| `getGenres` + `getSongsByGenre` | 按流派浏览 | P1 |

### 发现 / 列表
| 端点 | 用途 | 阶段 |
|------|------|------|
| `getAlbumList2` | 多类型列表：random/newest/highest/frequent/recent/byYear/byGenre/alphabetical | P3 |
| `getRandomSongs` | 随机音乐 | P3 |
| `getStarred2` (OS) | 收藏（歌曲/专辑/艺术家） | P3 |
| `getNowPlaying` | 其他客户端正在播放 | P3 |
| `getVideos` | 视频（若服务端启用） | P4 |

### 搜索
| 端点 | 用途 | 阶段 |
|------|------|------|
| `search3` | 统一搜索（歌曲/专辑/艺术家） | P1 |

### 播放列表
| 端点 | 用途 | 阶段 |
|------|------|------|
| `getPlaylists` / `getPlaylist` | 读取服务端歌单 | P3 |
| `createPlaylist` / `updatePlaylist` / `deletePlaylist` | 歌单 CRUD | P3 |
| `savePlayQueue` / `getPlayQueue` (OS) | 播放队列云端同步 | P3 |

### 媒体
| 端点 | 用途 | 阶段 |
|------|------|------|
| `stream` | 播放（`maxBitRate`/`format`/`timeOffset`/`estimateContentLength`） | P1 |
| `download` | 下载原文件 | P4 |
| `getCoverArt` | 封面 | P1 |
| `getLyrics` / `getLyricsBySongId` (OS) | 歌词（含结构化同步歌词） | P3 |
| `hls` (OS) | HLS 流（备选） | P4 |

### 标注 / 历史
| 端点 | 用途 | 阶段 |
|------|------|------|
| `star` / `unstar` | 星标收藏 | P3 |
| `setRating` | 评分 1–5 | P3 |
| `scrobble` | 播放历史回传 | P3 |

### 书签 / 分享 / 电台
| 端点 | 用途 | 阶段 |
|------|------|------|
| `getBookmarks` / `createBookmark` / `deleteBookmark` | 记住播放位置 | P3 |
| `getShares` / `createShare` / `updateShare` / `deleteShare` | 分享链接 | P4 |
| `getInternetRadioStations` / `createInternetRadioStation` / `updateInternetRadioStation` / `deleteInternetRadioStation` | 互联网电台 | P3 |

### 用户 / 维护
| 端点 | 用途 | 阶段 |
|------|------|------|
| `getUser` / `getUsers` | 用户信息（多用户场景） | P4 |
| `startScan` | 触发曲库扫描 | P4 |

## 配置项清单

> 全部由 `SettingsService` 持久化到 `settings` 表（key-value），密码加密存储。

### 服务器连接
| 配置项 | 类型 / 默认 | 说明 |
|--------|-------------|------|
| 服务器列表 | Server[] | 多个服务器（名称 + 地址），可增删改 |
| 当前服务器 | int/guid | 选中的服务器 id |
| 用户名 | string | 当前服务器登录用户 |
| 密码 | string（加密） | 当前服务器登录密码 |
| auth token 缓存 | string | salt + token，避免每次 MD5 |

### 播放
| 配置项 | 类型 / 默认 | 说明 |
|--------|-------------|------|
| 音量 | 0–100 / 100 | |
| 左右平衡 | -100~+100 / 0 | BASS pan |
| 回放速度 | 0.5–2.0 / 1.0 | BASS_FX tempo |
| 音调 | 半音偏移 / 0 | BASS_FX pitch |
| 播放模式 | 枚举 / 顺序 | 顺序 / 随机 / 循环 / 单曲 |
| 交叉淡入淡出时长 | 0–15s / 0 | crossfade |
| 无缝播放 | bool / true | gapless |
| ReplayGain 模式 | 关/曲目/专辑/智能 / 关 | |
| ReplayGain preamp | dB / 0 | 增益偏移 |

### 音效（EQ / DSP）
| 配置项 | 类型 / 默认 | 说明 |
|--------|-------------|------|
| EQ 开关 | bool / off | |
| EQ 10 段增益 | float[10] / 0 | 31Hz–16kHz |
| EQ 预设 | 枚举 / 自定义 | 摇滚/流行/古典/人声/重低音/自定义 |
| DSP 开关（各效果独立） | bool[] | 混响/回声/合唱/立体声扩展/压缩器 |
| DSP 参数 | 各效果参数 | 混响强度/回声延迟/合唱深度等 |

### 流式 / 网络
| 配置项 | 类型 / 默认 | 说明 |
|--------|-------------|------|
| 网络质量 | 枚举 / 原始 | 原始/高/中/低 → maxBitRate+format |
| 缓冲大小 | KB / 默认 | BASS 流缓冲 |

### 路径 / 目录（均带默认值，可改）
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| 数据目录 | `%APPDATA%\subsonic-player\` | SQLite 数据库、settings |
| 缓存目录 | `%LOCALAPPDATA%\subsonic-player\cache\` | 封面/歌词/元数据/临时流缓冲 |
| 下载目录 | `%USERPROFILE%\Music\` | 下载歌曲（若支持） |
| 日志目录 | `%LOCALAPPDATA%\subsonic-player\logs\` | 运行日志 |
| EQ 预设目录 | `%APPDATA%\subsonic-player\eq\` | EQ 预设文件 |
| 播放列表导入/导出目录 | 下载目录 | 导入导出播放列表 |

> 约定：路径可为空或非法时回退到默认值；启动时创建所需目录。

### 外观 / 交互
| 配置项 | 类型 / 默认 | 说明 |
|--------|-------------|------|
| 主题 | 枚举 / 深色 | 深色 / 浅色 / 跟随系统 |
| 语言 | 枚举 / 简体中文 | |
| 睡眠定时器 | 分钟 / 关 | |
| 全局快捷键 | 按键映射 | |
| EQ 预设导入/导出 | 文件路径 | |

## SQLite 表

`artists`、`albums`、`songs`、`playlists`、`playlist_songs`、`play_history`、`favorites`、`settings`、`lyrics_cache`。

## 界面规划

### 整体布局（主窗口，三栏 + 底部播放栏）
```
┌────────────────────────────────────────────────────┐
│ 顶栏：搜索框 │ 服务器切换 │ 登录状态 │ 设置          │
├──────────┬─────────────────────────────────────────┤
│ 左侧导航  │         主内容区（随导航切换）          │
│  (菜单)  │                                         │
├──────────┴─────────────────────────────────────────┤
│ 底部播放栏：封面 │ 控制+进度 │ 模式 │ 音效 │ 音量   │
└────────────────────────────────────────────────────┘
```

### 顶栏
| 元素 | 说明 |
|------|------|
| 全局搜索框 | 回车跳转搜索页（`search3`） |
| 服务器切换 | 下拉选择服务器 / 快速切换 |
| 登录状态 | 连接成功 / 失败提示，点击打开服务器设置 |
| 设置按钮 | 打开设置页 |

### 左侧导航栏
- 顶部：应用 Logo + 当前服务器名（点击切换服务器）
- 菜单项：**发现 / 正在播放 / 专辑 / 艺术家 / 歌曲 / 播放列表 / 收藏 / 电台 / 搜索**
- 底部：设置、主题切换

### 各页面设计
| 页面 | 内容 / 控件 | 交互 |
|------|-------------|------|
| 发现（默认首页） | 随机音乐卡片 + 最近添加 / 最高评分 / 最常播放 / 按年代 / 按流派（`getAlbumList2` 多类型） | 点击专辑进详情 |
| 正在播放 | 大封面 + 曲目信息 + 歌词（可切换）+ 队列列表 | 切歌词/队列视图 |
| 专辑 | 网格/列表切换、封面墙 | 双击播放 |
| 艺术家 | 网格（头像/名称） | 点击进详情 |
| 歌曲 | 列表（标题/艺术家/专辑/时长/评分），表头排序 | 双击播放 |
| 播放列表 | 服务端歌单列表 + 智能列表入口 | 点击进歌单详情 |
| 收藏 | Tab：歌曲 / 专辑 / 艺术家（`getStarred2`） | 取消收藏 |
| 电台 | 互联网电台列表（`getInternetRadioStations`） | 点击播放 |
| 搜索 | 结果分 Tab：歌曲/专辑/艺术家 | 回车/实时搜索 |

### 详情页
| 详情页 | 布局 | 操作 |
|--------|------|------|
| 专辑详情 | 左侧大封面 + 专辑信息（年份/流派/评分）；右侧曲目列表 | 播放全部 / 收藏 / 加入队列 / 评分 |
| 艺术家详情 | 头像 + 简介 + 相似歌手（`getArtistInfo2`）；专辑网格 + Top Songs | 播放 Top / 全部 |

### 底部播放栏
| 区域 | 元素 |
|------|------|
| 左 | 封面缩略图 + 标题/艺术家 + 收藏按钮 |
| 中 | 上一首 / 播放暂停 / 下一首、进度条（可拖拽 + 时间）、播放模式按钮 |
| 右 | 歌词开关、队列按钮、音效(EQ)按钮、音量滑杆 |

### 弹层 / 对话框
| 名称 | 内容 |
|------|------|
| EQ 面板 | 10 段滑杆 + 实时频谱 + 预设下拉 + EQ 开关 |
| 迷你播放器 | 悬浮小窗：封面 + 控制 + 进度 |
| 服务器设置 | 服务器列表 / 地址 / 用户名 / 密码（可多服务器） |
| 设置页 | 分类：连接 / 播放 / 音效 / 流式 / 外观 / 路径 |
| 歌单编辑 | 新建/重命名/删除、歌曲拖拽排序 |
| 分享 | 生成分享链接（`createShare`） |

### 交互细节
- 右键菜单：播放 / 下一首播放 / 加入队列 / 收藏 / 评分 / 添加到播放列表 / 下载 / 分享 / 查看详情
- 双击播放、拖拽入队 / 拖拽排序歌单
- 全局快捷键 + 媒体键（见配置项）
- 深色/浅色主题即时切换

### 视觉 / 配色（深色 OLED + 封面主色渐变）
- 背景三层：`#0E0E11`（app）/ `#17171B`（surface）/ `#1E1E24`（card），边框 `#2E2E38`
- 强调色「播放绿」`#22C55E`（覆盖 Fluent `SystemAccentColor`，进度条/选中态/EQ 频谱自动取色）
- 文字三阶：`#F5F5F7` / `#A1A1AA` / `#6B6B76`
- **封面主色渐变底图**：提取当前曲目封面主色（ImageSharp 解码 → 缩放求平均色/网格采样）→ `LinearGradientBrush` 主色渐变到 `#0E0E11` 作为全局底图，上压约 75% 不透明深色遮罩保证可读
- 入口：`MainWindowViewModel.SetCoverBackground(colorHex)`，切歌时由 PlaybackService 调用
- 已实现为低保真骨架，P1 接真实封面后自动生效

## 里程碑

| 阶段 | 内容 |
|------|------|
| P1 | 工程骨架 + SubsonicClient + 基础播放（stream）+ 曲库浏览 + 封面 |
| P2 | Mixer 架构 + Gapless + Crossfade + EQ + 频谱 + DSP |
| P3 | 播放列表/收藏/歌词/历史 |
| P4 | 托盘/SMTC/快捷键/主题/定时器 |

## 功能规划（按里程碑细化）

### P1 — 工程骨架 + 基础能力
| 功能 | 说明 | 验收 |
|------|------|------|
| 解决方案骨架 | .NET 10 + Avalonia 12.0.3 + Semi.Avalonia，分层 Models/Services/ViewModels/Views | 可编译运行空窗口 |
| 配置系统 | SettingsService + 设置页（服务器连接、目录，均带默认值） | 配置持久化、重启生效 |
| SubsonicClient | salt+MD5 认证、XML 解析、ping/artists/albums/songs/coverArt/search3 | 连接成功并拉取曲库 |
| 曲库浏览 | 艺术家→专辑→歌曲三级导航，网格/列表切换 | 浏览流畅、封面显示 |
| 曲库深度浏览 | 艺术家详情 `getArtistInfo2`（简介/相似歌手/图片）、专辑详情 `getAlbumInfo2`、`getTopSongs`、`getSimilarSongs2`、按流派 `getGenres`+`getSongsByGenre` | 详情页与流派浏览正常 |
| 基础播放 | `stream` 播放，播放/暂停/上/下首、进度条、音量 | 完整播放一首歌 |
| SQLite 缓存 | artists/albums/songs/settings 表，元数据+封面秒开 | 二次启动免等待 |

### P2 — 音频引擎（音效核心）
| 功能 | 说明 | 验收 |
|------|------|------|
| Mixer 架构 | BASS Mixer + DECODE 流 | 多路流进 mixer |
| Gapless 无缝 | mixer 预加载下一曲，专辑内零间断 | 连续曲目无 gap |
| Crossfade | 两路流音量斜坡交叉，0–15s 可调 | 淡入淡出平滑 |
| 播放队列 | 拖拽排序/清空/移除 + 顺序/随机/循环/单曲 | 模式切换正确 |
| 10 段 EQ | BASS_DX8_PARAMEQ 链式 + 预设 | EQ 实时生效 |
| 频谱可视化 | BASS_ChannelGetData FFT → Avalonia 绘制 | 实时刷新 |
| DSP | 混响/回声/合唱/立体声扩展/压缩器 | 各效果独立开关 |
| ReplayGain | 读 track gain + preamp 偏移 | 音量一致 |
| 速度/音调 | BASS_FX tempo/pitch | 可调回放 |

### P3 — 列表 / 收藏 / 歌词 / 历史
| 功能 | 说明 | 验收 |
|------|------|------|
| 服务端歌单读取 | `getPlaylists`/`getPlaylist` 读取服务器现有歌单，展示列表+详情，歌单歌曲可播放/入队 | 歌单正常显示并可播放 |
| 播放列表 CRUD | 新建/编辑/删除歌单，`create/update/deletePlaylist` 同步回服务器，本地收藏同步 | 与服务端一致 |
| 智能列表 | 最近添加/收藏/高评分 | 自动更新 |
| 星标 + 评分 | star/unstar、setRating | 收藏状态回传 |
| 滚动歌词 | OpenSubsonic `getLyrics`，时间戳同步 | 逐行滚动 |
| 播放历史 + scrobble | 本地历史 + scrobble 回传 | 记录准确 |
| 发现页 | `getAlbumList2` 多类型（最新/最高评分/最常播放/最近添加/随机/按年代/按流派）+ `getRandomSongs` 随机音乐 + `getStarred2` 收藏 + `getNowPlaying` | 各类型列表正常 |
| 播放队列同步 | `savePlayQueue`/`getPlayQueue` 云端队列同步 | 队列可恢复/跨设备 |
| 书签 | `getBookmarks`/`createBookmark`/`deleteBookmark` 记住播放位置 | 续播 |
| 互联网电台 | `getInternetRadioStations` 浏览/播放 | 电台可播 |

### P4 — 系统集成 + 专业增强
| 功能 | 说明 | 验收 |
|------|------|------|
| 系统托盘 + 迷你播放条 | 后台常驻 + 迷你控制 | 托盘可控制播放 |
| Windows SMTC | 任务栏缩略图 + 媒体键 | 媒体键可用 |
| 全局快捷键 | 自定义按键映射 | 全局生效 |
| 主题 | 深色/浅色 | 即时切换 |
| 睡眠定时器 | 定时停止播放 | 到点停止 |
| EQ 预设导入/导出 | 预设文件读写 | 可复用 |
| 流式缓冲设置 | 网络质量 → maxBitRate/format | 低带宽可听 |
| 多服务器管理 | 添加/切换服务器 | 一键切换 |
| 下载 | `download` 下载原文件到下载目录 | 可下载 |
| 分享 | `getShares`/`createShare`/`updateShare`/`deleteShare` 分享链接 | 分享可用 |

## 兼容客户端参考（可选对照）

手机：Symfonium、substreamer、play:Sub；桌面：dSub、Sonixd。
