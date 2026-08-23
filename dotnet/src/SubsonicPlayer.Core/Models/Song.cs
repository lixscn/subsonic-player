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
}
