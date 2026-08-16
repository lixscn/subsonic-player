using System.Collections.ObjectModel;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using SubsonicPlayer.Services;

namespace SubsonicPlayer.ViewModels;

public partial class BookmarksViewModel : ViewModelBase
{
    [ObservableProperty]
    private ObservableCollection<BookmarkItemViewModel> _bookmarks = new();

    [ObservableProperty]
    private string _status = "";

    [ObservableProperty]
    private bool _hasStatus;

    partial void OnStatusChanged(string value) => HasStatus = !string.IsNullOrEmpty(value);

    public BookmarksViewModel()
    {
        _ = LoadAsync();
    }

    private async Task LoadAsync()
    {
        var music = AppServices.Music;
        if (music is null)
        {
            Status = "未配置音乐服务";
            return;
        }

        Status = "连接中...";
        try
        {
            if (!await music.ConnectAsync())
            {
                Status = "连接失败";
                return;
            }

            Status = "";
            var bookmarks = await music.GetBookmarksAsync();
            foreach (var b in bookmarks)
                Bookmarks.Add(new BookmarkItemViewModel(b));

            if (Bookmarks.Count == 0)
                Status = "暂无书签";
        }
        catch
        {
            Status = "暂无书签";
        }
    }
}
