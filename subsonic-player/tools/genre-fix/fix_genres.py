#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fix_genres.py —— 用 AcoustID 声纹识别给音乐文件补「真实流派(genre)」。

链路：
    fpcalc 算声纹  →  AcoustID 查录音/发行组 MBID  →  MusicBrainz 取 genres  →  mutagen 写回文件标签

为什么这么绕：
    文件里的 genre 常常被下载站写成来源水印（wusunk.com收藏 / kuwo / 论坛名），
    这种值里没有任何「真流派」信息，只能靠声纹去外部库反查。

安全设计：
    * 默认【只预览不写】；必须显式加 --write 才会改文件。
    * --limit / --dry-run 先小范围验证命中率。
    * 结果缓存到 JSON，重复跑不重复查（省时间、少打 API）。
    * 只改 genre 一个字段，其它标签不动。默认跳过 WAV（要写加 --include-wav）。

依赖：
    python3 + mutagen        (pip install mutagen)
    fpcalc 可执行文件        (chromaprint；apt install libchromaprint-tools，或下载静态二进制)

用法：
    export ACOUSTID_KEY=你的key          # https://acoustid.org/new-application 免费申请
    python3 fix_genres.py /volume1/music --limit 50                 # 试跑 50 首（不写）
    python3 fix_genres.py /volume1/music --limit 50 --write         # 真写这 50 首
    python3 fix_genres.py /volume1/music --only-missing --write     # 只补「空/未知/水印」的，全量
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

ACOUSTID_API = "https://api.acoustid.org/v2/lookup"
MB_API = "https://musicbrainz.org/ws/2"
# MusicBrainz 强制要求带可识别 User-Agent（带联系方式更好，避免被封）
MB_UA = "SubsonicPlayer-GenreFix/1.0 ( https://github.com/subsonic-player )"

AUDIO_EXT = {".flac", ".mp3", ".m4a", ".mp4", ".ogg", ".oga", ".opus"}
WAV_EXT = {".wav"}
DSD_EXT = {".dsf", ".dff"}          # 基本不支持写标签，默认跳过

# 「不是真流派」的值：网址 / 下载站 / 论坛 / 合集水印 / 纯标点 / 未知
JUNK_RE = re.compile(
    r"(?i)(https?://|www\.|\.(com|cn|net|org|cc|top|xyz|vip)\b"
    r"|wusunk|kuwo|kugou|qqmusic|论坛|分享|合购|下载|收藏|资源|美图|无损|hires)"
)
PUNCT_RE = re.compile(r"^[\s\W_]+$", re.UNICODE)
UNKNOWN_VALUES = {"", "未知", "unknown", "other", "未知流派", "其他", "无", "n/a", "none"}


def is_junk_genre(v: str) -> bool:
    if v is None:
        return True
    s = v.strip()
    if s.lower() in UNKNOWN_VALUES:
        return True
    if PUNCT_RE.match(s):
        return True
    return bool(JUNK_RE.search(s))


# ---------------------------------------------------------------- fpcalc

def find_fpcalc(explicit):
    if explicit:
        return explicit if os.path.exists(explicit) else None
    for name in ("fpcalc", "fpcalc.exe"):
        from shutil import which
        p = which(name)
        if p:
            return p
    here = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fpcalc")
    return here if os.path.exists(here) else None


def run_fpcalc(fpcalc: str, path: str, timeout: int = 120):
    """返回 (duration:int, fingerprint:str) 或 None。"""
    try:
        out = subprocess.run(
            [fpcalc, "-json", "-length", "120", path],
            capture_output=True, text=True, timeout=timeout,
        )
        if out.returncode != 0 or not out.stdout.strip():
            return None
        data = json.loads(out.stdout)
        dur = int(round(data.get("duration", 0)))
        fp = data.get("fingerprint")
        if not fp or dur <= 0:
            return None
        return dur, fp
    except Exception:
        return None


# ---------------------------------------------------------------- HTTP

def http_json(url: str, data: dict | None = None, ua: str = MB_UA, timeout: int = 30):
    body = urllib.parse.urlencode(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, headers={"User-Agent": ua})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def acoustid_lookup(key: str, duration: int, fingerprint: str):
    """返回 results 列表（含 recordings/releasegroups）。"""
    try:
        j = http_json(ACOUSTID_API, {
            "client": key,
            "meta": "recordings+releasegroups+compress",
            "duration": duration,
            "fingerprint": fingerprint,
        }, ua=MB_UA)
        if j.get("status") != "ok":
            return []
        return j.get("results", []) or []
    except Exception:
        return []


def mb_genres(entity: str, mbid: str, cache: dict):
    """取 MusicBrainz genres（entity: release-group / artist），返回 [(name, count)]。"""
    ck = f"{entity}:{mbid}"
    if ck in cache:
        return cache[ck]
    time.sleep(1.1)                      # MusicBrainz 限速 1 req/s
    try:
        url = f"{MB_API}/{entity}/{mbid}?inc=genres&fmt=json"
        j = http_json(url)
        gs = [(g["name"], int(g.get("count", 0))) for g in (j.get("genres") or [])]
    except Exception:
        gs = []
    cache[ck] = gs
    return gs


def best_genre(results, mb_cache, min_score=0.5):
    """从 AcoustID 结果里挑流派：优先 release-group genres，再退到 artist genres。"""
    for r in sorted(results, key=lambda x: x.get("score", 0), reverse=True):
        if r.get("score", 0) < min_score:
            continue
        for rec in (r.get("recordings") or []):
            # 1) 发行组流派（更贴曲风）
            for rg in (rec.get("releasegroups") or []):
                gs = mb_genres("release-group", rg["id"], mb_cache)
                if gs:
                    gs.sort(key=lambda x: -x[1])
                    return gs[0][0]
            # 2) 退到艺术家流派（华语歌这里反而常更全）
            for a in (rec.get("artists") or []):
                gs = mb_genres("artist", a["id"], mb_cache)
                if gs:
                    gs.sort(key=lambda x: -x[1])
                    return gs[0][0]
    return None


# ---------------------------------------------------------------- mutagen 读写

def read_genre(path: str):
    ext = os.path.splitext(path)[1].lower()
    try:
        if ext == ".flac":
            from mutagen.flac import FLAC
            v = FLAC(path).get("genre")
            return v[0] if v else ""
        if ext in (".mp3",):
            from mutagen.id3 import ID3
            t = ID3(path).get("TCON")
            return str(t.text[0]) if t and getattr(t, "text", None) else ""
        if ext in (".m4a", ".mp4"):
            from mutagen.mp4 import MP4
            v = MP4(path).tags.get("\xa9gen") if MP4(path).tags else None
            return v[0] if v else ""
        if ext in (".ogg", ".oga", ".opus"):
            from mutagen import File as MFile
            f = MFile(path)
            v = f.get("genre") if f else None
            return v[0] if v else ""
        if ext in WAV_EXT:
            from mutagen.wave import WAVE
            t = WAVE(path).tags.get("TCON") if WAVE(path).tags else None
            return str(t.text[0]) if t and getattr(t, "text", None) else ""
    except Exception:
        return ""
    return ""


def write_genre(path: str, genre: str) -> bool:
    ext = os.path.splitext(path)[1].lower()
    try:
        if ext == ".flac":
            from mutagen.flac import FLAC
            f = FLAC(path)
            f["genre"] = [genre]
            f.save()
            return True
        if ext == ".mp3":
            from mutagen.id3 import ID3, TCON, ID3NoHeaderError
            try:
                t = ID3(path)
            except ID3NoHeaderError:
                t = ID3()
            t.setall("TCON", [TCON(encoding=3, text=[genre])])
            t.save(path)
            return True
        if ext in (".m4a", ".mp4"):
            from mutagen.mp4 import MP4
            f = MP4(path)
            f["\xa9gen"] = [genre]
            f.save()
            return True
        if ext in (".ogg", ".oga", ".opus"):
            from mutagen import File as MFile
            f = MFile(path)
            f["genre"] = [genre]
            f.save()
            return True
        if ext in WAV_EXT:
            from mutagen.wave import WAVE
            from mutagen.id3 import TCON
            f = WAVE(path)
            if f.tags is None:
                from mutagen.id3 import ID3
                f.add_tags()
            f.tags.setall("TCON", [TCON(encoding=3, text=[genre])])
            f.save()
            return True
    except Exception as e:
        print(f"    ! 写入失败: {e}")
    return False


# ---------------------------------------------------------------- 主流程

def iter_files(root: str, include_wav: bool):
    exts = set(AUDIO_EXT) | (WAV_EXT if include_wav else set())
    for dirpath, _dirs, files in os.walk(root):
        for fn in files:
            ext = os.path.splitext(fn)[1].lower()
            if ext in exts:
                yield os.path.join(dirpath, fn)


def main():
    ap = argparse.ArgumentParser(description="用 AcoustID 声纹给音乐文件补真实流派")
    ap.add_argument("root", help="媒体根目录")
    ap.add_argument("--write", action="store_true", help="真正写回文件（默认只预览）")
    ap.add_argument("--limit", type=int, default=0, help="最多处理多少个文件（0=全部）")
    ap.add_argument("--only-missing", action="store_true",
                    help="只处理 genre 为空/未知/水印的文件（跳过已有正常流派的）")
    ap.add_argument("--clear-junk", action="store_true",
                    help="查不到流派时，顺手把原有的垃圾 genre 清空")
    ap.add_argument("--include-wav", action="store_true", help="也处理 .wav（默认为保险跳过）")
    ap.add_argument("--min-score", type=float, default=0.5, help="AcoustID 匹配最低分（默认 0.5）")
    ap.add_argument("--fpcalc", default=None, help="fpcalc 可执行文件路径")
    ap.add_argument("--cache", default=None, help="缓存文件（默认 <root>/.genre-fix-cache.json）")
    args = ap.parse_args()

    key = os.environ.get("ACOUSTID_KEY", "").strip()
    if not key:
        print("错误：请先设置环境变量 ACOUSTID_KEY（https://acoustid.org/new-application 免费申请）")
        sys.exit(2)

    fpcalc = find_fpcalc(args.fpcalc)
    if not fpcalc:
        print("错误：找不到 fpcalc。apt install libchromaprint-tools，或放一个 fpcalc 到脚本同目录。")
        sys.exit(2)

    try:
        import mutagen  # noqa: F401
    except ImportError:
        print("错误：缺少 mutagen。pip install mutagen")
        sys.exit(2)

    cache_path = args.cache or os.path.join(args.root, ".genre-fix-cache.json")
    mb_cache_path = cache_path + ".mb.json"
    cache = {}
    if os.path.exists(cache_path):
        try:
            cache = json.load(open(cache_path, encoding="utf-8"))
        except Exception:
            cache = {}
    mb_cache = {}
    if os.path.exists(mb_cache_path):
        try:
            mb_cache = json.load(open(mb_cache_path, encoding="utf-8"))
        except Exception:
            mb_cache = {}

    files = list(iter_files(args.root, args.include_wav))
    if args.limit:
        files = files[: args.limit]

    print(f"目录: {args.root}")
    print(f"待处理: {len(files)} 个文件  模式: {'写入' if args.write else '仅预览(dry-run)'}")
    print("-" * 78)

    matched = unmatched = skipped = written = cleared = 0
    for i, path in enumerate(files, 1):
        rel = os.path.relpath(path, args.root)
        old = cache.get(path)
        if old is None:
            old_genre = read_genre(path)
        else:
            old_genre = old.get("old", "")

        if args.only_missing and old_genre and not is_junk_genre(old_genre):
            skipped += 1
            continue

        new_genre = None
        if path in cache and cache[path].get("new"):
            new_genre = cache[path]["new"]
        else:
            fp = run_fpcalc(fpcalc, path)
            if fp:
                dur, fingerprint = fp
                results = acoustid_lookup(key, dur, fingerprint)
                new_genre = best_genre(results, mb_cache, args.min_score)
            cache[path] = {"old": old_genre, "new": new_genre or ""}
            try:
                json.dump(cache, open(cache_path, "w", encoding="utf-8"), ensure_ascii=False)
            except Exception:
                pass
            try:
                json.dump(mb_cache, open(mb_cache_path, "w", encoding="utf-8"), ensure_ascii=False)
            except Exception:
                pass
            time.sleep(0.34)      # AcoustID 友好限速

        if new_genre:
            matched += 1
            print(f"[{i}/{len(files)}] {rel}")
            print(f"      {old_genre!r}  ->  {new_genre}")
            if args.write and new_genre != old_genre:
                if write_genre(path, new_genre):
                    written += 1
        else:
            unmatched += 1
            if args.clear_junk and old_genre and is_junk_genre(old_genre):
                cleared += 1
                print(f"[{i}/{len(files)}] {rel}")
                print(f"      查不到流派，清空垃圾值 {old_genre!r}")
                if args.write:
                    write_genre(path, "")

    print("-" * 78)
    print(f"命中流派: {matched}   未命中: {unmatched}   跳过(已有正常流派): {skipped}")
    print(f"{'已写入' if args.write else '将写入'}: {written}   清空垃圾: {cleared}")
    if not args.write:
        print("\n这是预览。确认无误后加 --write 再跑一次即可真正写回。")
    if files and matched / max(1, matched + unmatched) < 0.3:
        print("\n提示：命中率偏低。华语歌在 MusicBrainz 的流派覆盖较弱，"
              "可考虑再挂 Last.fm（lastgenre）或网易云做兜底。")


if __name__ == "__main__":
    main()
