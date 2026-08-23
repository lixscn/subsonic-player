using System;
using System.Collections;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Media;
using SubsonicPlayer.ViewModels;

namespace SubsonicPlayer.Services;

/// <summary>
/// 播放队列拖拽排序：长按进入拖动（重排 + 插入指示线），短按释放则点击播放。
/// 用「长按阈值」区分点击与拖动，避免干扰点击换歌。
/// </summary>
public static class QueueDragHelper
{
    private const int LongPressMs = 400;

    private static SongItemViewModel? _dragItem;
    private static Control? _dragSource;
    private static bool _isDragging;

    /// <summary>按下：记录拖动候选并启动长按检测。</summary>
    public static void OnQueueItemPressed(PointerPressedEventArgs e, SongItemViewModel item, Control source)
    {
        _dragItem = item;
        _dragSource = source;
        _isDragging = false;
        e.Pointer.Capture(source);
        _ = WaitForLongPressAsync();
    }

    private static async Task WaitForLongPressAsync()
    {
        try
        {
            await Task.Delay(LongPressMs);
            if (_dragItem is not null && _dragSource is not null && !_isDragging)
            {
                _isDragging = true;

                // 视觉反馈：整个项浮起
                _dragSource.RenderTransform = new TranslateTransform(0, -4);
                _dragSource.ZIndex = 100;
                _dragSource.Opacity = 0.95;
            }
        }
        catch
        {
        }
    }

    /// <summary>移动：长按进入拖动后，实时更新插入指示线。</summary>
    public static void OnQueueItemMoved(PointerEventArgs e, ListBox listBox)
    {
        if (_dragItem is null || _dragSource is null || !_isDragging)
            return;

        UpdateIndicator(e, listBox);
    }

    /// <summary>释放：拖动则重排队列，否则点击播放。</summary>
    public static void OnQueueItemReleased(PointerReleasedEventArgs e, ListBox listBox)
    {
        if (_dragItem is null)
            return;

        var item = _dragItem;
        var wasDragging = _isDragging;
        _dragItem = null;
        _isDragging = false;

        if (_dragSource is not null)
        {
            _dragSource.RenderTransform = null;
            _dragSource.ZIndex = 0;
            _dragSource.Opacity = 1;
            e.Pointer.Capture(null);
            _dragSource = null;
        }
        ClearIndicators();

        if (wasDragging)
        {
            var toIndex = ResolveTargetIndex(e, listBox);
            var fromIndex = AppServices.Playback.QueueIndexOf(item.Song.Id);
            if (fromIndex >= 0 && toIndex >= 0)
                AppServices.Playback.MoveQueueItem(fromIndex, toIndex);
        }
        else
        {
            // 短按 → 点击播放
            item.PlayCommand.Execute(null);
        }
    }

    private static void UpdateIndicator(PointerEventArgs e, ListBox listBox)
    {
        ClearIndicators();
        var index = HitIndex(e.GetPosition(listBox), listBox);
        if (index >= 0 && index < AppServices.Playback.QueueItems.Count)
            AppServices.Playback.QueueItems[index].IsDropIndicator = true;
    }

    private static int ResolveTargetIndex(PointerEventArgs e, ListBox listBox)
        => HitIndex(e.GetPosition(listBox), listBox);

    private static int HitIndex(Point pos, ListBox listBox)
    {
        var hit = listBox.InputHitTest(pos) as Control;
        while (hit is not null and not ListBoxItem)
            hit = hit.Parent as Control;

        if (hit is ListBoxItem container && listBox.Items is IList items)
        {
            var index = items.IndexOf(container.DataContext);
            return index;
        }
        return -1;
    }

    private static void ClearIndicators()
    {
        foreach (var item in AppServices.Playback.QueueItems)
            item.IsDropIndicator = false;
    }
}
