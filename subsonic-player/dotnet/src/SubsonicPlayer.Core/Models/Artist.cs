using System.Collections.Generic;

namespace SubsonicPlayer.Models;

public class Artist
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public int AlbumCount { get; set; }
}

public class ArtistIndex
{
    public string Name { get; set; } = "";
    public List<Artist> Artists { get; set; } = new();
}
