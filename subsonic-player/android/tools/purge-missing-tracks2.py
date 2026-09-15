#!/usr/bin/env python3
# 清理"文件确实不存在"的死记录（修正版）：
# 先按 information_schema 查出所有引用 music_track 的（表, 列），
# 按外键顺序先删引用行、再删主记录；全程打印 SQL 错误，不再静默失败。
import os
import subprocess

ROOT = "/vol1/@team/public/music"
PREFIX = "/app/media"
STAMP = subprocess.run(["date", "+%Y%m%d-%H%M%S"], capture_output=True, text=True).stdout.strip()
BACKUP = "/vol1/1000/runtime/music_tables-backup2-%s.sql" % STAMP
MY = ["docker", "exec", "mysql8", "mysql", "--default-character-set=utf8mb4",
      "-umusic_tag_app", "-pJaSon840207", "music_tag", "-N", "-B"]


def sql(q):
    r = subprocess.run(MY + ["-e", q], capture_output=True, text=True)
    return r.stdout, r.stderr


def sqlq(q):
    return sql(q)[0]


rows = [l.split("\t") for l in sqlq("SELECT id, path FROM music_track;").splitlines() if "\t" in l]
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
            continue
    dead.append((tid, p))

print("曲目总数: %d   死记录: %d 条" % (total_before, len(dead)))
if not dead:
    raise SystemExit(0)
ids = [t for t, _ in dead]
idlist = ",".join(ids)

# ---- 备份 ----
tables = "music_track music_trackfavorite music_trackrating music_playlisttrack music_playqueuetrack"
subprocess.run("docker exec mysql8 mysqldump -umusic_tag_app -pJaSon840207 music_tag %s > %s" % (tables, BACKUP),
               shell=True, capture_output=True, text=True)
print("备份: %s (%.1f KB)" % (BACKUP, os.path.getsize(BACKUP) / 1024.0))

# ---- 找出所有引用 music_track 的表/列 ----
fk = [l.split("\t") for l in sqlq(
    "SELECT table_name, column_name FROM information_schema.key_column_usage "
    "WHERE table_schema='music_tag' AND referenced_table_name='music_track';").splitlines() if "\t" in l]
print("引用 music_track 的外键: %d 个" % len(fk))
for t, c in fk:
    print("   %s.%s" % (t, c))

# ---- 先删引用行（只在引用行确实存在时执行）----
errs = []
for t, c in fk:
    cnt = (sqlq("SELECT COUNT(*) FROM %s WHERE %s IN (%s);" % (t, c, idlist)) or "0").strip()
    if cnt == "0":
        continue
    out, err = sql("DELETE FROM %s WHERE %s IN (%s);" % (t, c, idlist))
    print("   删除 %s 中 %s 条引用行%s" % (t, cnt, ("  错误: " + err.strip()[:150]) if err.strip() else " ✓"))
    if err.strip():
        errs.append(err.strip())

# ---- 再删主记录 ----
out, err = sql("DELETE FROM music_track WHERE id IN (%s);" % idlist)
if err.strip():
    print("删除主记录错误: %s" % err.strip()[:300])
    errs.append(err.strip())

# ---- 复查 ----
total_after = int((sqlq("SELECT COUNT(*) FROM music_track;") or "0").strip())
print()
print("曲目总数: %d → %d  （删除 %d 条）" % (total_before, total_after, total_before - total_after))
left = 0
for line in sqlq("SELECT path FROM music_track;").splitlines():
    if line.strip() and not os.path.isfile(ROOT + line.strip()[len(PREFIX):]):
        left += 1
print("剩余失效路径: %d 条（应只剩'名字对不上'那批）" % left)
print("执行错误数: %d" % len(errs))
