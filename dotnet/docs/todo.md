# 待办事项 / 未完成清单

> 汇总所有「已提出但未实施 / 未完成」的事项。**仅记录，实施时再决定。**

## 一、UI / 体验类

### 1. 歌词展示方式改为主流方式（2026-08 提出）
- 现状：正在播放页歌词放在中间窄列，`ListBox` 逐行居中，当前行绿色高亮
- 问题：用户认为「放歌词的方式不好」
- 目标：改为主流歌词展示（参考 musiver / 网易云 / Spotify）：
  - 歌词区更大、更居中，滚动显示
  - **当前行高亮放大 + 前后行渐隐**（卡拉 OK 式）
  - 可参考 musiver「正在播放页」：大封面 + 歌词滚动 + 顶部标签页（推荐/歌曲/歌词）

### 2. 动态封面底图（2026-08 提出）
- 想法：把应用底图动态换成正在播放的封面图（模糊封面 + 暗色遮罩）
- 网上例子：网易云 / Spotify / Apple Music 的 blurred album art background（主流做法，会好看）
- 可行性：Avalonia `BlurEffect` + 已有封面加载（`Playback.CurrentCover`）+ 已预留 `SetCoverBackground` 主色渐变
- 实现要点：背景层 `Image` + `BlurEffect(Radius≈40)` + `OverlayBrush` 遮罩；主色渐变可作降级
- 状态：已确认可行，未实施

### 3. Sonixd / Musiver 借鉴项（详见 `docs/ui-reference-analysis.md`）
- ⭐ 专辑/艺术家详情页**顶部大横幅卡片**（封面+名字+统计+播放/收藏/下载按钮）
- ⭐ 播放队列表格化（`# 时长 标题 艺术家 专辑` 多列 + 表头排序）
- ⭐ 专辑库**自适应封面网格**（hover 播放）
- 中：当前播放行高亮加强（灰底+标题绿）、左侧导航底部播放迷你卡、艺术家统计
- 低：正在播放标签页切换、黑胶唱片式封面

## 二、功能 / 协议类

### 4. AudioStation（群晖）协议
- README / PLAN.md 标注「规划中」，未实现
- 用户此前「先不搞」，暂缓

### 5. 多平台适配（P5/P6 里程碑）
- 已加入 `PLAN.md` P5；详细方案见 `docs/multi-platform-plan.md`（P5 桌面三平台 + P6 移动端专项）
- **进行中**：单核心（`src/SubsonicPlayer.Core`）+ 多界面（`src/SubsonicPlayer.Desktop` / `src/SubsonicPlayer.Mobile`）拆分解耦已完成，桌面双 TFM 编译通过
- 剩余：macOS/Linux 平台验证、移动端 workload 安装与启动、SMTC 缩略图（见 §7）

### 6. Emby / Plex 收藏页适配
- 收藏页依赖「喜欢的音乐」歌单（Subsonic 特性），Emby/Plex 下会显示「未找到歌单」
- 未处理

### 7. SMTC 封面缩略图
- SMTC 已做标题/艺术家/状态/媒体键，任务栏缩略图封面未做

### 8. 智能推荐算法增强
- 当前仅「收藏艺术家的未收藏歌曲」，可加：播放历史权重、流派/风格相似度、`getSimilarSongs2` 协同

### 9. 中文歌词源补充
- LRCLIB 以英文为主，中文歌可能搜不到；可加网易云/QQ音乐歌词源

## 三、发布 / 验证类

### 10. 网络歌词搜索验证
- 已修复「服务端歌词接口抛异常跳过网络兜底」的 bug，待用户实际验证
- 调试日志：`%APPDATA%\subsonic-player\crash.log`、`drag.log`

### 11. GitHub Release 上传
- 发布包 `SubsonicPlayer-win-x64.zip`（53MB）在项目根目录
- 用户选择手动上传 Releases（tag `v1.0.0` 已推送）
