using System.IO;
using Microsoft.Data.Sqlite;

namespace SubsonicPlayer.Services;

/// <summary>
/// SQLite 曲库缓存数据库。管理连接与表结构，供后续缓存曲库元数据 / 播放历史使用。
/// 数据库文件位于数据目录下的 library.db。
/// </summary>
public class LibraryDatabase
{
    private readonly string _dbPath;

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

        "CREATE INDEX IF NOT EXISTS idx_songs_album_id  ON songs(album_id)",
        "CREATE INDEX IF NOT EXISTS idx_songs_artist_id ON songs(artist_id)",
        "CREATE INDEX IF NOT EXISTS idx_albums_artist_id ON albums(artist_id)",
        "CREATE INDEX IF NOT EXISTS idx_play_history_song ON play_history(song_id)",
    };
}
