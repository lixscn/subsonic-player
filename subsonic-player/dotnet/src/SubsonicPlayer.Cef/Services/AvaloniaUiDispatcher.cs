using System;
using Avalonia.Threading;
using SubsonicPlayer.Services;

namespace SubsonicPlayer;

/// <summary>Avalonia UI 线程调度器（注入给 Core 的 IActionDispatcher，让 UiTimer 回调跑在主线程）。</summary>
public sealed class AvaloniaUiDispatcher : IActionDispatcher
{
    public void Post(Action action) => Dispatcher.UIThread.Post(action);
}
