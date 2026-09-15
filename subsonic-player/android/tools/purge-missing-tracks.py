#!/usr/bin/env python3
# 清理曲库里"文件确实不存在"的死记录。
#
# 安全约束：
#   1) 先整表备份（mysqldump）到 runtime 目录，可完整回滚；
#   2) 只删「文件不存在 且 同目录同扩展名一个候选都没有」的记录 ——
#      有候选的（名字对不上）一律不删，那是能救回来的歌；
#   3) 删除后用 LEFT JOIN 查关联表残留，只报告不动（是否清理交给人决定）。
import os
import subprocess

ROOT = "/vol1/@team/public/music"
PREFIX = "/app/media"
STAMP = subprocess.run(["date", "+%Y%m%d-%H%M%S"], capture_output=True, text=True).stdout.strip()
BACKUP = "/vol1/1000/runtime/music_tables-backup-%s.sql" % STAMP
MY = ["docker", "exec", "mysql8", "mysql", "--default-character-set=utf8mb4",
      "-umusic_tag_app", "-pJaSon840207", "music_tag", "-N", "-B"]


def sql(q):
    r = subprocess.run(MY + ["-e", q], capture_output=True, text=True)
    return r.stdout


rows = [l.split("\t") for l in sql("SELECT id, path FROM music_track;").splitlines() if "\t" in l]
total_before = len(rows)
dead = []
for parts in rows:
    if len(parts) < 2:
        continue
    tid, p = parts[0], parts[1]
    f = ROOT + p[len(PREFIX):]
    if os.path.isfile(f):
        continue
    d, base = os.path.dirname(f), os.path.basename(f)
    if os.path.isdir(d):
        ext = os.path.splitext(base)[1].lower()
        try:
            cands = [x for x in os.listdir(d) if os.path.splitext(x)[1].lower() == ext]
        except Exception:
            cands = []
        if cands:
            continue          # 有候选 → 名字对不上而已，不删
    dead.append((tid, p))

print("曲目总数: %d" % total_before)
print("判定为死记录: %d 条" % len(dead))
if not dead:
    print("没有需要清理的，结束。")
    raise SystemExit(0)

# ---- 1) 备份（相关表整表 dump，便于回滚）----
tables = "music_track music_album music_trackfavorite music_trackrating music_playlisttrack music_playqueuetrack"
r = subprocess.run(
    "docker exec mysql8 mysqldump -umusic_tag_app -pJaSon840207 music_tag %s > %s" % (tables, BACKUP),
    shell=True, capture_output=True, text=True)
size = os.path.getsize(BACKUP) if os.path.exists(BACKUP) else 0
print("备份: %s (%.1f KB)" % (BACKUP, size / 1024.0))
if size < 1000:
    print("备份异常，中止删除 ✗")
    raise SystemExit(1)

# ---- 2) 删除 ----
ids = ",".join(t for t, _ in dead)
sql("DELETE FROM music_track WHERE id IN (%s);" % ids)

# ---- 3) 复查 ----
left = 0
for line in sql("SELECT path FROM music_track;").splitlines():
    if line.strip() and not os.path.isfile(ROOT + line.strip()[len(PREFIX):]):
        left += 1
total_after = int(sql("SELECT COUNT(*) FROM music_track;").strip() or 0)
print()
print("删除后曲目总数: %d （原 %d，减少 %d）" % (total_after, total_before, total_before - total_after))
print("剩余失效路径: %d 条（应为 49 条'名字对不上'那批）" % left)
print()
print("--- 已删除记录示例（前 12 条）---")
for tid, p in dead[:12]:
    print("  [%s] %s" % (tid, p))

# ---- 4) 关联表残留检查（只报告）----
print()
print("--- 关联表残留检查 ---")
for t, col in [("music_trackfavorite", "track_id"), ("music_trackrating", "track_id"),
               ("music_playlisttrack", "track_id"), ("music_playqueuetrack", "track_id")]:
    chk = sql("SELECT COUNT(*) FROM %s a LEFT JOIN music_track b ON a.%s=b.id WHERE b.id IS NULL;" % (t, col))
    chk = (chk or "").strip()
    if chk and chk != "0":
        print("  %s: %s 条孤儿（指向已删除的曲目）" % (t, chk))
    elif chk == "0":
        print("  %s: 无孤儿 ✓" % t)
    else:
        print("  %s: 查询跳过（列名可能不同）" % t)
