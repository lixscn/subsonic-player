using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using Microsoft.Data.Sqlite;
using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

/// <summary>
/// SQLite 曲库缓存数据库。管理连接与表结构，供后续缓存曲库元数据 / 播放历史使用。
/// 数据库文件位于数据目录下的 library.db。
/// </summary>
public class LibraryDatabase
{
    private readonly string _dbPath;

    /// <summary>全局写锁：串行化所有写操作，避免 SQLite 锁竞争导致线程池膨胀。</summary>
    private static readonly object WriteLock = new();

    public LibraryDatabase(string dataDir)
    {
        _dbPath = Path.Combine(dataDir, "library.db");
    }

    /// <summary>打开数据库连接（调用方负责释放）。</summary>
    public SqliteConnection OpenConnection()
    {
        var builder = new SqliteConnectionStringBuilder { DataSource = _dbPath };
        var conn = new SqliteConnection(builder.ToString());
        conn.Open();
        return conn;
    }

    /// <summary>初始化表结构（幂等，可重复调用）。</summary>
    public void Initialize()
    {
        using var conn = OpenConnection();
        using var tx = conn.BeginTransaction();

        foreach (var sql in SchemaStatements)
        {
            using var cmd = conn.CreateCommand();
            cmd.Transaction = tx;
            cmd.CommandText = sql;
            cmd.ExecuteNonQuery();
        }

        tx.Commit();
    }

    private static readonly string[] SchemaStatements =
    {
        """
        CREATE TABLE IF NOT EXISTS artists (
            id          TEXT PRIMARY KEY,
            name        TEXT NOT NULL,
            album_count INTEGER NOT NULL DEFAULT 0,
            cached_at   TEXT NOT NULL
        )
        """,

        """
        CREATE TABLE IF NOT EXISTS albums (
            id           TEXT PRIMARY KEY,
            name         TEXT NOT NULL,
            artist       TEXT,
            artist_id    TEXT,
            cover_art_id TEXT,
            song_count   INTEGER NOT NULL DEFAULT 0,
            duration     INTEGER NOT NULL DEFAULT 0,
            year         INTEGER NOT NULL DEFAULT 0,
            genre        TEXT,
            cached_at    TEXT NOT NULL
        )
        """,

        """
        CREATE TABLE IF NOT EXISTS songs (
            id           TEXT PRIMARY KEY,
            title        TEXT NOT NULL,
            artist       TEXT,
            artist_id    TEXT,
            album        TEXT,
            album_id     TEXT,
            duration     INTEGER NOT NULL DEFAULT 0,
            track        INTEGER NOT NULL DEFAULT 0,
            year         INTEGER NOT NULL DEFAULT 0,
            cover_art_id TEXT,
            suffix       TEXT,
            bit_rate     INTEGER NOT NULL DEFAULT 0,
            cached_at    TEXT NOT NULL
        )
        """,

        """
        CREATE TABLE IF NOT EXISTS play_history (
            id        INTEGER PRIMARY KEY AUTOINCREMENT,
            song_id   TEXT NOT NULL,
            played_at TEXT NOT NULL
        )
        """,

        """
        CREATE TABLE IF NOT EXISTS lyrics_cache (
            song_key   TEXT PRIMARY KEY,
            synced_lrc TEXT,
            plain_text TEXT,
            cached_at  TEXT NOT NULL
        )
        """,

        """
        CREATE TABLE IF NOT EXISTS playback_state (
            id       INTEGER PRIMARY KEY CHECK (id = 1),
            data     TEXT NOT NULL,
            saved_at TEXT NOT NULL
        )
        """,

        "CREATE INDEX IF NOT EXISTS idx_songs_album_id  ON songs(album_id)",
        "CREATE INDEX IF NOT EXISTS idx_songs_artist_id ON songs(artist_id)",
        "CREATE INDEX IF NOT EXISTS idx_albums_artist_id ON albums(artist_id)",
        "CREATE INDEX IF NOT EXISTS idx_play_history_song ON play_history(song_id)",
    };

    /// <summary>缓存歌曲元数据（UPSERT），供最近播放等本地读取秒开。</summary>
    public void UpsertSong(Song song)
    {
        if (string.IsNullOrEmpty(song.Id))
            return;

        lock (WriteLock)
        {
            using var conn = OpenConnection();
            using var cmd = conn.CreateCommand();
            cmd.CommandText = """
                INSERT INTO songs (id, title, artist, artist_id, album, album_id, duration, track, year, cover_art_id, suffix, bit_rate, cached_at)
                VALUES ($id, $title, $artist, $artist_id, $album, $album_id, $duration, $track, $year, $cover, $suffix, $bit_rate, $cached_at)
                ON CONFLICT(id) DO UPDATE SET
                    title=$title, artist=$artist, artist_id=$artist_id, album=$album, album_id=$album_id,
                    duration=$duration, track=$track, year=$year, cover_art_id=$cover, suffix=$suffix,
                    bit_rate=$bit_rate, cached_at=$cached_at
                """;
            cmd.Parameters.AddWithValue("$id", song.Id);
            cmd.Parameters.AddWithValue("$title", song.Title);
            cmd.Parameters.AddWithValue("$artist", song.Artist);
            cmd.Parameters.AddWithValue("$artist_id", song.ArtistId);
            cmd.Parameters.AddWithValue("$album", song.Album);
            cmd.Parameters.AddWithValue("$album_id", song.AlbumId);
            cmd.Parameters.AddWithValue("$duration", song.Duration);
            cmd.Parameters.AddWithValue("$track", song.Track);
            cmd.Parameters.AddWithValue("$year", song.Year);
            cmd.Parameters.AddWithValue("$cover", song.CoverArtId);
            cmd.Parameters.AddWithValue("$suffix", song.Suffix);
            cmd.Parameters.AddWithValue("$bit_rate", song.BitRate);
            cmd.Parameters.AddWithValue("$cached_at", DateTime.UtcNow.ToString("o"));
            cmd.ExecuteNonQuery();
        }
    }

    /// <summary>批量缓存歌曲元数据（单连接 + 单事务，供队列建立时一次性写入）。</summary>
    public void BatchUpsertSongs(IReadOnlyList<Song> songs)
    {
        if (songs.Count == 0)
            return;

        lock (WriteLock)
        {
            using var conn = OpenConnection();
            using var tx = conn.BeginTransaction();
            using var cmd = conn.CreateCommand();
            cmd.Transaction = tx;
            cmd.CommandText = """
                INSERT INTO songs (id, title, artist, artist_id, album, album_id, duration, track, year, cover_art_id, suffix, bit_rate, cached_at)
                VALUES ($id, $title, $artist, $artist_id, $album, $album_id, $duration, $track, $year, $cover, $suffix, $bit_rate, $cached_at)
                ON CONFLICT(id) DO UPDATE SET
                    title=$title, artist=$artist, artist_id=$artist_id, album=$album, album_id=$album_id,
                    duration=$duration, track=$track, year=$year, cover_art_id=$cover, suffix=$suffix,
                    bit_rate=$bit_rate, cached_at=$cached_at
                """;
            var cachedAt = DateTime.UtcNow.ToString("o");
            foreach (var song in songs)
            {
                if (string.IsNullOrEmpty(song.Id))
                    continue;
                cmd.Parameters.Clear();
                cmd.Parameters.AddWithValue("$id", song.Id);
                cmd.Parameters.AddWithValue("$title", song.Title);
                cmd.Parameters.AddWithValue("$artist", song.Artist);
                cmd.Parameters.AddWithValue("$artist_id", song.ArtistId);
                cmd.Parameters.AddWithValue("$album", song.Album);
                cmd.Parameters.AddWithValue("$album_id", song.AlbumId);
                cmd.Parameters.AddWithValue("$duration", song.Duration);
                cmd.Parameters.AddWithValue("$track", song.Track);
                cmd.Parameters.AddWithValue("$year", song.Year);
                cmd.Parameters.AddWithValue("$cover", song.CoverArtId);
                cmd.Parameters.AddWithValue("$suffix", song.Suffix);
                cmd.Parameters.AddWithValue("$bit_rate", song.BitRate);
                cmd.Parameters.AddWithValue("$cached_at", cachedAt);
                cmd.ExecuteNonQuery();
            }
            tx.Commit();
        }
    }

    /// <summary>写入一条播放历史。</summary>
    public void RecordPlay(string songId)
    {
        if (string.IsNullOrEmpty(songId))
            return;

        lock (WriteLock)
        {
            using var conn = OpenConnection();
            using var cmd = conn.CreateCommand();
            cmd.CommandText = "INSERT INTO play_history (song_id, played_at) VALUES ($id, $at)";
            cmd.Parameters.AddWithValue("$id", songId);
            cmd.Parameters.AddWithValue("$at", DateTime.UtcNow.ToString("o"));
            cmd.ExecuteNonQuery();
        }
    }

    /// <summary>清空播放历史。</summary>
    public void ClearHistory()
    {
        lock (WriteLock)
        {
            using var conn = OpenConnection();
            using var cmd = conn.CreateCommand();
            cmd.CommandText = "DELETE FROM play_history";
            cmd.ExecuteNonQuery();
        }
    }

    /// <summary>保存网络歌词缓存（syncedLrc 与 plainText 至少其一非空）。</summary>
    public void SaveLyrics(string songKey, string syncedLrc, string plainText)
    {
        if (string.IsNullOrEmpty(songKey))
            return;

        lock (WriteLock)
        {
            using var conn = OpenConnection();
            using var cmd = conn.CreateCommand();
            cmd.CommandText = """
                INSERT INTO lyrics_cache (song_key, synced_lrc, plain_text, cached_at)
                VALUES ($key, $lrc, $text, $at)
                ON CONFLICT(song_key) DO UPDATE SET
                    synced_lrc=$lrc, plain_text=$text, cached_at=$at
                """;
            cmd.Parameters.AddWithValue("$key", songKey);
            cmd.Parameters.AddWithValue("$lrc", (object?)syncedLrc ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$text", (object?)plainText ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$at", DateTime.UtcNow.ToString("o"));
            cmd.ExecuteNonQuery();
        }
    }

    /// <summary>读取歌词缓存（未命中返回 null）。</summary>
    public (string SyncedLrc, string PlainText)? GetLyrics(string songKey)
    {
        if (string.IsNullOrEmpty(songKey))
            return null;

        using var conn = OpenConnection();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT synced_lrc, plain_text FROM lyrics_cache WHERE song_key = $key";
        cmd.Parameters.AddWithValue("$key", songKey);

        using var reader = cmd.ExecuteReader();
        if (!reader.Read())
            return null;

        return (
            reader.IsDBNull(0) ? "" : reader.GetString(0),
            reader.IsDBNull(1) ? "" : reader.GetString(1));
    }

    /// <summary>按 id 读取缓存的歌曲元数据（未缓存返回 null）。</summary>
    public Song? GetSong(string id)
    {
        if (string.IsNullOrEmpty(id))
            return null;

        using var conn = OpenConnection();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = """
            SELECT title, artist, artist_id, album, album_id, duration, track, year, cover_art_id, suffix, bit_rate
            FROM songs WHERE id = $id
            """;
        cmd.Parameters.AddWithValue("$id", id);

        using var reader = cmd.ExecuteReader();
        if (!reader.Read())
            return null;

        return new Song
        {
            Id = id,
            Title = reader.GetString(0),
            Artist = reader.GetString(1),
            ArtistId = reader.GetString(2),
            Album = reader.GetString(3),
            AlbumId = reader.GetString(4),
            Duration = reader.GetInt32(5),
            Track = reader.GetInt32(6),
            Year = reader.GetInt32(7),
            CoverArtId = reader.GetString(8),
            Suffix = reader.GetString(9),
            BitRate = reader.GetInt32(10),
        };
    }

    /// <summary>保存播放状态（队列歌曲 id + 当前索引 + 播放位置，单行覆盖）。</summary>
    public void SavePlaybackState(IReadOnlyList<string> songIds, int currentIndex, double positionSeconds)
    {
        if (songIds.Count == 0)
        {
            ClearPlaybackState();
            return;
        }

        var data = JsonSerializer.Serialize(new PlaybackStateData
        {
            SongIds = songIds.ToList(),
            CurrentIndex = currentIndex,
            PositionSeconds = positionSeconds,
        });

        lock (WriteLock)
        {
            using var conn = OpenConnection();
            using var cmd = conn.CreateCommand();
            cmd.CommandText = """
                INSERT INTO playback_state (id, data, saved_at) VALUES (1, $data, $at)
                ON CONFLICT(id) DO UPDATE SET data=$data, saved_at=$at
                """;
            cmd.Parameters.AddWithValue("$data", data);
            cmd.Parameters.AddWithValue("$at", DateTime.UtcNow.ToString("o"));
            cmd.ExecuteNonQuery();
        }
    }

    /// <summary>读取上次播放状态（无记录返回 null）。</summary>
    public (List<string> SongIds, int CurrentIndex, double PositionSeconds)? LoadPlaybackState()
    {
        using var conn = OpenConnection();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT data FROM playback_state WHERE id = 1";

        if (cmd.ExecuteScalar() is not string json || string.IsNullOrEmpty(json))
            return null;

        try
        {
            var state = JsonSerializer.Deserialize<PlaybackStateData>(json);
            if (state is null || state.SongIds.Count == 0)
                return null;
            return (state.SongIds, state.CurrentIndex, state.PositionSeconds);
        }
        catch
        {
            return null;
        }
    }

    /// <summary>清空播放状态。</summary>
    public void ClearPlaybackState()
    {
        lock (WriteLock)
        {
            using var conn = OpenConnection();
            using var cmd = conn.CreateCommand();
            cmd.CommandText = "DELETE FROM playback_state";
            cmd.ExecuteNonQuery();
        }
    }

    private sealed class PlaybackStateData
    {
        public List<string> SongIds { get; set; } = new();
        public int CurrentIndex { get; set; }
        public double PositionSeconds { get; set; }
    }

    /// <summary>读取最近播放的歌曲（按最新播放时间去重排序）。</summary>
    public List<Song> GetRecentSongs(int limit = 50)
    {
        var result = new List<Song>();
        using var conn = OpenConnection();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = """
            SELECT s.id, s.title, s.artist, s.artist_id, s.album, s.album_id, s.duration, s.track, s.year, s.cover_art_id, s.suffix, s.bit_rate
            FROM play_history h
            JOIN songs s ON s.id = h.song_id
            GROUP BY h.song_id
            ORDER BY MAX(h.played_at) DESC
            LIMIT $limit
            """;
        cmd.Parameters.AddWithValue("$limit", limit);

        using var reader = cmd.ExecuteReader();
        while (reader.Read())
        {
            result.Add(new Song
            {
                Id = reader.GetString(0),
                Title = reader.GetString(1),
                Artist = reader.GetString(2),
                ArtistId = reader.GetString(3),
                Album = reader.GetString(4),
                AlbumId = reader.GetString(5),
                Duration = reader.GetInt32(6),
                Track = reader.GetInt32(7),
                Year = reader.GetInt32(8),
                CoverArtId = reader.GetString(9),
                Suffix = reader.GetString(10),
                BitRate = reader.GetInt32(11),
            });
        }
        return result;
    }
}
