using System;
using System.ComponentModel;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Threading;
using Avalonia.VisualTree;
using SubsonicPlayer.Services;
using SubsonicPlayer.ViewModels;

namespace SubsonicPlayer.Views;

public partial class NowPlayingView : UserControl
{
    private DispatcherTimer? _lyricsScrollTimer;
    private DispatcherTimer? _queueScrollTimer;

    public NowPlayingView()
    {
        InitializeComponent();
        DataContextChanged += OnDataContextChanged;
        AppServices.Playback.PropertyChanged += OnPlaybackPropertyChanged;
    }

    /// <summary>当前播放索引变化时，把队列当前行平滑滚动到可视区中部。</summary>
    private void OnPlaybackPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(PlaybackService.CurrentIndex))
            Dispatcher.UIThread.Post(ScrollQueueToCurrent);
    }

    private void OnDataContextChanged(object? sender, EventArgs e)
    {
        if (DataContext is NowPlayingViewModel vm)
            vm.PropertyChanged += OnVmPropertyChanged;
    }

    /// <summary>歌词行切换时，把当前行平滑滚动到可视区中部（主流歌词效果：缓动插值）。</summary>
    private void OnVmPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(NowPlayingViewModel.CurrentLineIndex))
            Dispatcher.UIThread.Post(ScrollLyricsToCurrentLine);
    }

    private void ScrollLyricsToCurrentLine()
    {
        var vm = DataContext as NowPlayingViewModel;
        if (LyricsList is null || vm is null || vm.CurrentLineIndex < 0)
            return;

        var scroll = LyricsList.FindDescendantOfType<ScrollViewer>();
        if (scroll is null)
            return;

        var container = LyricsList.ContainerFromIndex(vm.CurrentLineIndex);
        if (container is null)
            return;

        var target = container.Bounds.Top - (scroll.Viewport.Height - container.Bounds.Height) / 2;
        target = Math.Max(0, target);

        // 平滑滚动：ease-out 插值，避免歌词行切换时生硬跳转
        _lyricsScrollTimer?.Stop();
        _lyricsScrollTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(16) };
        _lyricsScrollTimer.Tick += (_, _) =>
        {
            var current = scroll.Offset.Y;
            var next = current + (target - current) * 0.22;
            if (Math.Abs(target - next) < 0.5)
            {
                scroll.Offset = new Vector(0, target);
                _lyricsScrollTimer.Stop();
                _lyricsScrollTimer = null;
            }
            else
            {
                scroll.Offset = new Vector(0, next);
            }
        };
        _lyricsScrollTimer.Start();
    }

    /// <summary>把播放队列的当前行平滑滚动到可视区中部（与歌词居中一致）。</summary>
    private void ScrollQueueToCurrent()
    {
        var playback = AppServices.Playback;
        if (QueueList is null || playback.CurrentIndex < 0)
            return;

        var scroll = QueueList.FindDescendantOfType<ScrollViewer>();
        if (scroll is null)
            return;

        var container = QueueList.ContainerFromIndex(playback.CurrentIndex);
        if (container is null)
        {
            if (QueueList.SelectedItem is { } item)
                QueueList.ScrollIntoView(item);
            return;
        }

        var target = container.Bounds.Top - (scroll.Viewport.Height - container.Bounds.Height) / 2;
        target = Math.Max(0, target);

        _queueScrollTimer?.Stop();
        _queueScrollTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(16) };
        _queueScrollTimer.Tick += (_, _) =>
        {
            var current = scroll.Offset.Y;
            var next = current + (target - current) * 0.22;
            if (Math.Abs(target - next) < 0.5)
            {
                scroll.Offset = new Vector(0, target);
                _queueScrollTimer.Stop();
                _queueScrollTimer = null;
            }
            else
            {
                scroll.Offset = new Vector(0, next);
            }
        };
        _queueScrollTimer.Start();
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
}
