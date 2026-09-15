# 洗流派（genre）工具

用 **AcoustID 声纹识别**给音乐文件补「真实流派」，顺手清掉下载站写进 `genre` 的水印
（`wusunk.com收藏` / `kuwo` / 论坛名 之类）。

链路：`fpcalc 声纹 → AcoustID 查录音/发行组 → MusicBrainz genres → mutagen 写回标签`

---

## 0. 先决条件

| 需要 | 说明 |
|---|---|
| `python3` | NAS 上一般自带；群晖可装「Python3」套件 |
| `mutagen` | `pip3 install mutagen`（写标签用） |
| `fpcalc` | chromaprint 的声纹工具，**必须** |
| `ACOUSTID_KEY` | 免费申请：https://acoustid.org/new-application |

### 装 fpcalc

- **Debian/Ubuntu（含多数 NAS 的 Docker/Entware）**：`apt install libchromaprint-tools`
- **群晖 / 无包管理器**：下载静态二进制放进脚本同目录并命名 `fpcalc`：
  - https://acoustid.org/chromaprint （选 Linux x86_64 的 `chromaprint-fpcalc-*-linux-x86_64.tar.gz`）
  - 解压后把 `fpcalc` 拷过来：`chmod +x fpcalc`
- **确认**：`fpcalc -version`

> 如果你的 NAS 是 ARM，选对应的 aarch64 版本。

---

## 1. 把脚本拷到 NAS

```bash
# 在电脑上（脚本在 仓库/tools/genre-fix/）
scp fix_genres.py 你的用户@NAS:/volume1/music/_tools/
# 若用静态 fpcalc，也一起拷：
scp fpcalc 你的用户@NAS:/volume1/music/_tools/
```

---

## 2. 先小范围"试跑"，看命中率（**这一步最关键**）

```bash
cd /volume1/music/_tools
export ACOUSTID_KEY=你的key

# 只跑 50 首，仅预览，不动文件
python3 fix_genres.py /volume1/music --limit 50
```

看输出里 `命中流派 / 未命中` 的比例：

- **命中率 ≥ 60%** → 可以放心全量跑（第 3 步）。
- **命中率很低（华语歌常见）** → 见文末「命中率低怎么办」。

---

## 3. 正式跑（全量）

```bash
# 先只补「空/未知/水印」的，真正写回；并顺手清掉查不到的水印值
python3 fix_genres.py /volume1/music --only-missing --clear-junk --write
```

参数说明：

| 参数 | 作用 |
|---|---|
| `--write` | **不加就只是预览**，不动任何文件（安全设计） |
| `--only-missing` | 跳过已有正常流派的，只补空/未知/水印的 |
| `--clear-junk` | 查不到流派时，把垃圾 genre 清空（不留水印） |
| `--include-wav` | 也处理 `.wav`（默认跳过，见下） |
| `--limit N` | 只处理前 N 个 |
| `--min-score 0.6` | 提高 AcoustID 匹配门槛（更准、更少） |

其它：

- 结果缓存在 `<root>/.genre-fix-cache.json`，**重复跑不会重复查**；想重查就删掉它。
- 只改 `genre` 一个字段，其它标签不动。
- 中断了直接再跑，会接着来。

---

## 4. 让服务器看到新标签

改完文件后，让 **Music Tag Web 重新扫描**（或至少刷新它自己的库），否则它的 Subsonic 接口
还会返回旧 genre。之后 App 的「风格」页就是新的流派了。

---

## 关于 WAV（重点）

你库里约 **125 首是 `.wav`**（`Wave[Id3]`）。WAV 没有标准标签容器，写 genre 属于"非标准操作"，
**默认跳过**。要试就加 `--include-wav`，但：

- 有些播放器/服务器不认 WAV 里的 ID3 → 可能白写；
- 更彻底的解法是把 WAV **转成 FLAC** 再处理（无损，体积还更小）。

---

## 命中率低怎么办（华语歌常见）

MusicBrainz 对**中文歌**的流派标注覆盖率不高。若试跑命中率低，两条路：

1. **换/加流派数据源**：
   - **Last.fm**（`track.getTopTags` / `artist.getTopTags`）——华语 tag 较多，需要免费 API key；
   - **网易云**——本项目 App 已用它取艺术家照片，可扩展查专辑/歌曲风格；
   - 做法：AcoustID 只用来**确认是哪首歌/哪个艺术家**，流派改从上面这些源取。
2. **只做"去垃圾"**：不追求真流派，先把水印清掉：
   ```bash
   python3 fix_genres.py /volume1/music --clear-junk --write
   ```
   （不带 AcoustID 也能清——但脚本仍需要 key，因为我们按链路走；纯清理版可再给一个精简脚本。）

---

## 另：不想写脚本的话

- **beets**（Docker 镜像 `linuxserver/beets` 自带 fpcalc）：`chroma` + `lastgenre` 插件，
  用 `beet mbsync`（原地同步标签，**不要** `beet import`，那会移动/重排文件）。
- **Mp3tag**（Windows）：挂载 NAS 共享，用「动作/正则」批量改 genre —— 只能清理，不能补真流派。
