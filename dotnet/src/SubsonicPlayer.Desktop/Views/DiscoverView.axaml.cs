using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;

namespace SubsonicPlayer.Views;

public partial class DiscoverView : UserControl
{
    private bool _isDragging;
    private Point _dragStart;
    private double _dragStartOffset;

    public DiscoverView()
    {
        InitializeComponent();
    }

    private void OnScrollDragStart(object? sender, PointerPressedEventArgs e)
    {
        if (sender is not ScrollViewer sv)
            return;

        _isDragging = true;
        _dragStart = e.GetPosition(sv);
        _dragStartOffset = sv.Offset.X;
        e.Pointer.Capture(sv);
        e.Handled = true;
    }

    private void OnScrollDragMove(object? sender, PointerEventArgs e)
    {
        if (!_isDragging || sender is not ScrollViewer sv)
            return;

        var pos = e.GetPosition(sv);
        var delta = _dragStart.X - pos.X;
        sv.Offset = sv.Offset.WithX(_dragStartOffset + delta);
        e.Handled = true;
    }

    private void OnScrollDragEnd(object? sender, PointerReleasedEventArgs e)
    {
        _isDragging = false;
        if (sender is ScrollViewer sv)
            e.Pointer.Capture(null);
    }
}
