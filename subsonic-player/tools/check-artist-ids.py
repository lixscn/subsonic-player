"""Does the player's own cached library carry artist ids?

Why this check exists: the clickable artist name is only clickable when the payload has an artistId, and
whether the SERVER sends one is a fact about the server, not about the UI code. The app caches what it
received into `%APPDATA%\\subsonic-player\\library.db`, so that cache is an independent record of what the
server actually returned - no credentials and no running app needed to read it.

usage: python check-artist-ids.py <library.db>
"""

import sqlite3
import sys

db = sys.argv[1]
con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)

tables = [r[0] for r in con.execute("select name from sqlite_master where type='table'")]
print("tables:", tables)

if "songs" not in tables:
    print("no `songs` table in this cache")
    raise SystemExit(0)

columns = [r[1] for r in con.execute("pragma table_info(songs)")]
print("songs columns:", columns)

if "artist_id" not in columns:
    print("the cache does not store artist_id at all")
    raise SystemExit(0)

total = con.execute("select count(*) from songs").fetchone()[0]
with_id = con.execute("select count(*) from songs where artist_id is not null and artist_id != ''").fetchone()[0]
print(f"songs: {total}, with a non-empty artist_id: {with_id}")

print("--- a few rows (title / artist / artist_id) ---")
for row in con.execute("select title, artist, artist_id from songs limit 5"):
    print("   ", row)

print()
print("If `with a non-empty artist_id` is 0, the artist name cannot be a link on this server and the UI must")
print("fall back to plain text - which is exactly what artistLinkHtml does.")

# The second half of the question: `getArtistDetail` resolves the id from the ARTIST INDEX (getArtists), so a
# song's artist id that is not in that index would open nothing. Those are the featured-only collaborators, and
# they are the reason the click has a name-based fallback at all. Measuring the overlap says how much that
# fallback matters in practice.
if "artists" in tables:
    ids = {r[0] for r in con.execute("select id from artists")}
    song_ids = [r[0] for r in con.execute("select distinct artist_id from songs where artist_id is not null and artist_id != ''")]
    missing = [i for i in song_ids if i not in ids]
    print()
    print(f"artist index in this CACHE holds {len(ids)} artist(s); songs reference {len(song_ids)} distinct artist id(s)")
    if not ids:
        # ⚠️ Do not read this as "the server has no artists": the artists table is filled lazily, so an empty
        # one says nothing about the server. The live `getArtists` call is what `getArtistDetail` resolves
        # against, and only a running app can answer that. What IS answered here is the songs half: every
        # cached song carries an artist id, so the artist name has something to link to.
        print("  → the cache's artist table is empty (it is filled lazily), so this run cannot tell whether")
        print("    every id resolves; the name-based fallback in openArtistDetail covers the ones that do not.")
    else:
        print(f"referenced but NOT in the index: {len(missing)}"
              + (f"  e.g. {missing[:5]}" if missing else "  (so every click resolves directly)"))


