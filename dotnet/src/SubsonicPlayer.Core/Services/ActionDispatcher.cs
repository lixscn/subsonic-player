using System;

namespace SubsonicPlayer.Services;

/// <summary>
/// 主线程调度抽象：让 Core 能把回调解度到宿主主线程（桌面 AValonia UI 线程 / 移动端主线程），
/// 从而 Core 不依赖具体 UI 框架。由各端 App 启动时注入，未注入时默认内联执行。
/// </summary>
public interface IActionDispatcher
{
    /// <summary>把 action 调度到主线程（若当前已在主线程则直接执行）。</summary>
    void Post(Action action);
}

/// <summary>无调度器（未注入时兜底：内联执行）。</summary>
public sealed class InlineActionDispatcher : IActionDispatcher
{
    public void Post(Action action) => action?.Invoke();
}

/// <summary>
/// 主线程定时器：用 System.Threading.Timer 计时，把回调经 IActionDispatcher 调度到主线程，
/// 替代 Avalonia.Threading.DispatcherTimer，满足 Core 与 UI 框架解耦的同时保留主线程执行语义。
/// </summary>
public sealed class UiTimer
{
    private readonly System.Threading.Timer _timer;
    private readonly Action _callback;

    public UiTimer(Action callback)
    {
        _callback = callback;
        _timer = new System.Threading.Timer(_ => Post(), null, System.Threading.Timeout.Infinite, System.Threading.Timeout.Infinite);
    }

    private void Post() => AppServices.UiDispatcher.Post(_callback);

    /// <summary>启动周期性回调（intervalMs 毫秒）。</summary>
    public void Start(int intervalMs)
    {
        if (intervalMs <= 0)
            intervalMs = 1;
        _timer.Change(intervalMs, intervalMs);
    }

    /// <summary>停止。</summary>
    public void Stop() => _timer.Change(System.Threading.Timeout.Infinite, System.Threading.Timeout.Infinite);
}
