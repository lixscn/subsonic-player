using System;
using System.Collections.Generic;
using SubsonicPlayer.ViewModels;

namespace SubsonicPlayer.Services;

/// <summary>页面导航（含返回栈），用于从列表页跳转到详情页。</summary>
public static class NavigationService
{
    private static readonly Stack<ViewModelBase> _backStack = new();
    private static ViewModelBase? _current;

    public static event Action<ViewModelBase>? Navigated;

    public static void Navigate(ViewModelBase vm)
    {
        if (_current is not null)
            _backStack.Push(_current);
        _current = vm;
        Navigated?.Invoke(vm);
    }

    public static bool CanGoBack => _backStack.Count > 0;

    public static void GoBack()
    {
        if (_backStack.Count == 0)
            return;

        _current = _backStack.Pop();
        Navigated?.Invoke(_current);
    }
}
