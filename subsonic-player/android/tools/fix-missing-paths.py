#!/usr/bin/env python3
# 修复"数据库路径与磁盘文件名对不上"的遗留记录。
#
# 策略（保守优先）：
#   1) 只处理「该目录下同扩展名」的候选文件；
#   2) 用相似度打分，只有「最高分 ≥ 0.85 且明显高于第二名（差值 ≥ 0.10）」才自动修；
#   3) 有歧义的 → 只报告，不动；
#   4) 目录里没有候选 → 记为"文件确实不在了"，不动数据库（不删记录，避免误删数据）。
# 修改的是「数据库路径」而不是文件名 —— 让记录指向磁盘上真实存在的文件，风险最小。
import os
import re
import subprocess
import difflib

ROOT = "/vol1/@team/public/music"
PREFIX = "/app/media"
STAMP = subprocess.run(["date", "+%Y%m%d-%H%M%S"], capture_output=True, text=True).stdout.strip()
REPORT = "/vol1/1000/runtime/fix-fuzzy-%s.log" % STAMP
SQLFILE = "/tmp/fix-fuzzy-%s.sql" % STAMP

MYSQL = ["docker", "exec", "mysql8", "mysql", "--default-character-set=utf8mb4",
         "-umusic_tag_app", "-pJaSon840207", "music_tag", "-N", "-B"]


def sql(query):
    return subprocess.run(MYSQL + ["-e", query], capture_output=True, text=True).stdout


def norm(s):
    s = s.lower()
    s = re.sub(r"[\s\-_()\[\]（）【】、,，.。&＆'\"·]+", "", s)
    return s


rows = [l for l in sql("SELECT id, path FROM music_track;").splitlines() if l.strip()]
out = ["# 模糊匹配修复报告  " + STAMP, "曲目总数: %d" % len(rows), ""]

applied, ambiguous, missing = [], [], []
updates = []

for line in rows:
    parts = line.split("\t")
    if len(parts) < 2:
        continue
    tid, p = parts[0], parts[1]
    f = ROOT + p[len(PREFIX):]
    if os.path.isfile(f):
        continue
    d, base = os.path.dirname(f), os.path.basename(f)
    if not os.path.isdir(d):
        missing.append((p, "目录不存在"))
        continue
    try:
        files = os.listdir(d)
    except Exception as e:
        missing.append((p, "目录不可读"))
        continue
    ext = os.path.splitext(base)[1].lower()
    cands = [x for x in files if os.path.splitext(x)[1].lower() == ext]
    if not cands:
        missing.append((p, "同扩展名文件一个都没有"))
        continue
    scored = sorted(((difflib.SequenceMatcher(None, norm(base), norm(x)).ratio(), x) for x in cands),
                    reverse=True)
    best = scored[0]
    second = scored[1] if len(scored) > 1 else (0.0, "")
    if best[0] >= 0.85 and (best[0] - second[0]) >= 0.10:
        newp = p[:len(p) - len(base)] + best[1]
        updates.append("UPDATE music_track SET path='%s' WHERE id=%s;" % (newp.replace("'", "''"), tid))
        applied.append((p, best[1], best[0]))
    else:
        ambiguous.append((p, scored[:3]))

out.append("## 自动修复（唯一高相似命中）: %d 条" % len(applied))
for p, name, sc in applied:
    out.append("  [%.2f] %s\n        -> %s" % (sc, p, name))

out.append("")
out.append("## 有歧义（未动，需人工判断）: %d 条" % len(ambiguous))
for p, top in ambiguous:
    out.append("  %s" % p)
    for sc, name in top:
        out.append("      候选 [%.2f] %s" % (sc, name))

out.append("")
out.append("## 文件确实不存在: %d 条" % len(missing))
for p, why in missing[:40]:
    out.append("  %s   (%s)" % (p, why))
if len(missing) > 40:
    out.append("  ... 其余 %d 条见完整报告" % (len(missing) - 40))

if updates:
    with open(SQLFILE, "w") as fh:
        fh.write("START TRANSACTION;\n" + "\n".join(updates) + "\nCOMMIT;\n")
    subprocess.run(MYSQL + ["-e", "source " + SQLFILE], capture_output=True, text=True)
    r = subprocess.run("docker exec -i mysql8 mysql --default-character-set=utf8mb4 -umusic_tag_app -pJaSon840207 music_tag < " + SQLFILE,
                       shell=True, capture_output=True, text=True)
    out.append("")
    out.append("已执行 UPDATE: %d 条（stderr: %s）" % (len(updates), r.stderr.strip()[:200]))

with open(REPORT, "w") as fh:
    fh.write("\n".join(out) + "\n")

print("曲目总数: %d" % len(rows))
print("自动修复: %d 条" % len(applied))
print("有歧义:   %d 条" % len(ambiguous))
print("文件缺失: %d 条" % len(missing))
print("报告: %s" % REPORT)
print()
print("--- 自动修复明细（前 15 条）---")
for p, name, sc in applied[:15]:
    print("  [%.2f] %s" % (sc, os.path.basename(p)))
    print("         磁盘实际: %s" % name)

# 复查
still = 0
for line in sql("SELECT path FROM music_track;").splitlines():
    if line.strip() and not os.path.isfile(ROOT + line.strip()[len(PREFIX):]):
        still += 1
print()
print("复查：数据库路径失效 %d 条（处理前 93 条）" % still)
