namespace SubsonicPlayer.Models;

public class Song
{
    public string Id { get; set; } = "";
    public string Title { get; set; } = "";
    public string Artist { get; set; } = "";
    public string ArtistId { get; set; } = "";
    public string Album { get; set; } = "";
    public string AlbumId { get; set; } = "";
    public int Duration { get; set; }
    public int Track { get; set; }
    public int Year { get; set; }
    public string CoverArtId { get; set; } = "";
    public string Suffix { get; set; } = "";
    public int BitRate { get; set; }
    public string ContentType { get; set; } = "";
    public string Genre { get; set; } = "";

    /// <summary>ReplayGain 曲目增益（dB；double.NaN 表示服务端未提供）。</summary>
    public double ReplayGainTrackGain { get; set; } = double.NaN;

    /// <summary>ReplayGain 专辑增益（dB；double.NaN 表示未提供）。</summary>
    public double ReplayGainAlbumGain { get; set; } = double.NaN;

    /// <summary>ReplayGain 曲目峰值（线性，&gt;=1 可能削波；double.NaN 表示未提供）。</summary>
    public double ReplayGainTrackPeak { get; set; } = double.NaN;
}
