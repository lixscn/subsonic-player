using System.Collections;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using SubsonicPlayer.Services;
using SubsonicPlayer.ViewModels;

namespace SubsonicPlayer.Views;

public partial class MainWindow : Window
{
#if WINDOWS
    private readonly GlobalHotkeyManager _hotkeys = new();
#endif

    public MainWindow()
    {
        InitializeComponent();
#if WINDOWS
        Opened += (_, _) => _hotkeys.Register(this);
#endif
    }

    private void OnMinimizeClick(object? sender, RoutedEventArgs e)
        => WindowState = WindowState.Minimized;

    private void OnMaximizeClick(object? sender, RoutedEventArgs e)
        => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    private void OnCloseClick(object? sender, RoutedEventArgs e)
        => Close();

    private void OnTitleBarPressed(object? sender, PointerPressedEventArgs e)
    {
        if (e.GetCurrentPoint(this).Properties.IsLeftButtonPressed)
            BeginMoveDrag(e);
    }

    private void OnProgressReleased(object? sender, PointerReleasedEventArgs e)
    {
        if (sender is Slider slider)
            AppServices.Playback.Seek(slider.Value);
    }

    private void OnSearchKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && DataContext is MainWindowViewModel vm)
        {
            vm.SearchCommand.Execute(null);
            e.Handled = true;
        }
    }

    /// <summary>转到迷你播放器：打开迷你窗口并隐藏主窗口。</summary>
    private void OnMiniPlayerClick(object? sender, RoutedEventArgs e)
    {
        if (Application.Current is App app)
        {
            app.ShowMiniPlayer();
            Hide();
        }
    }

    // ---- 播放队列拖拽排序 ----

    private void OnQueueItemPressed(object? sender, PointerPressedEventArgs e)
    {
        if (sender is Control { DataContext: SongItemViewModel item })
            QueueDragHelper.OnQueueItemPressed(e, item, (Control)sender);
    }

    private void OnQueueItemMoved(object? sender, PointerEventArgs e)
    {
        var listBox = FindQueueListBox(sender as Control);
        if (listBox is not null)
            QueueDragHelper.OnQueueItemMoved(e, listBox);
    }

    private void OnQueueItemReleased(object? sender, PointerReleasedEventArgs e)
    {
        var listBox = FindQueueListBox(sender as Control);
        if (listBox is not null)
            QueueDragHelper.OnQueueItemReleased(e, listBox);
    }

    private static ListBox? FindQueueListBox(Control? c)
    {
        while (c is not null and not ListBox)
            c = c.Parent as Control;
        return c as ListBox;
    }

    private void OnTitleBarDoubleTapped(object? sender, TappedEventArgs e)
    {
        WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
    }

    private void OnKeyDown(object? sender, KeyEventArgs e)
    {
        switch (e.Key)
        {
            case Key.MediaPlayPause:
                AppServices.Playback.PlayPauseCommand.Execute(null);
                e.Handled = true;
                break;
            case Key.MediaNextTrack:
                AppServices.Playback.NextCommand.Execute(null);
                e.Handled = true;
                break;
            case Key.MediaPreviousTrack:
                AppServices.Playback.PreviousCommand.Execute(null);
                e.Handled = true;
                break;
            case Key.MediaStop:
                AppServices.Playback.PlayPauseCommand.Execute(null);
                e.Handled = true;
                break;
        }
    }
}
