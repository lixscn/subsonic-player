using System.Linq;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SubsonicPlayer.Models;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class BookmarkItemViewModel : ViewModelBase
{
    public Bookmark Bookmark { get; }

    public string Title => Bookmark.Songs.FirstOrDefault()?.Title ?? "未知曲目";
    public string Artist => Bookmark.Songs.FirstOrDefault()?.Artist ?? "";
    public string PositionText => FormatPosition(Bookmark.Position);
    public string CreatedText => Bookmark.Created?.ToString("g") ?? "";

    public BookmarkItemViewModel(Bookmark bookmark)
    {
        Bookmark = bookmark;
    }

    [RelayCommand]
    private void Play() => AppServices.Playback.PlayBookmark(Bookmark);

    [RelayCommand]
    private async Task DeleteAsync()
    {
        var music = AppServices.Music;
        var songId = Bookmark.Songs.FirstOrDefault()?.Id;
        if (music is null || string.IsNullOrEmpty(songId))
            return;

        try
        {
            await music.DeleteBookmarkAsync(songId);
        }
        catch
        {
            // 删除失败忽略
        }
    }

    private static string FormatPosition(long milliseconds)
    {
        var t = System.TimeSpan.FromMilliseconds(milliseconds);
        return t.TotalHours >= 1 ? t.ToString(@"h\:mm\:ss") : t.ToString(@"m\:ss");
    }
}
