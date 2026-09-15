using System;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Input.TextInput;
using Avalonia.Interactivity;
using Xilium.CefGlue;
using Xilium.CefGlue.Avalonia;

namespace SubsonicPlayer.Services;

/// <summary>
/// 继承 <see cref="AvaloniaCefBrowser"/> 的 IME 版本。
/// Avalonia 11 需要在控件获得焦点时通过 <see cref="InputElement.TextInputMethodClientRequestedEvent"/>
/// 主动“申请”成为输入法 client；DEFAULT 的 <c>AvaloniaCefBrowser</c> 不做这件事，
/// 于是 Windows TSF 输入法从未被激活 → 中文输入法在搜索框里打不出字（只能打英文/数字）。
///
/// 这里在控件获得/失去焦点时，把 <see cref="ImeTextInputMethodClient"/> 作为 client 上报给
/// 顶层的输入法。组合文本走 <c>SetPreeditText</c>（转发到 CEF 的 <c>ImeSetComposition</c>），
/// 提交文本走控件自带的 <c>TextInput</c> 事件链路（基类已实现）。其余按键逻辑不变，做兼容。
/// </summary>
public sealed class ImeAvaloniaCefBrowser : AvaloniaCefBrowser
{
    private readonly ImeTextInputMethodClient _imeClient;

    public ImeAvaloniaCefBrowser() : base(null)
    {
        // UnderlyingBrowser 是 protected（子类可访问）；浏览器未初始化时为空，故用懒求值。
        _imeClient = new ImeTextInputMethodClient(() => UnderlyingBrowser?.GetHost(), this);
        Focusable = true;
    }

    protected override void OnGotFocus(GotFocusEventArgs e)
    {
        base.OnGotFocus(e);
        RequestImeClient(_imeClient);
    }

    protected override void OnLostFocus(RoutedEventArgs e)
    {
        base.OnLostFocus(e);
        RequestImeClient(null);
    }

    /// <summary>向顶层输入法上报（或解除）IME client，从而激活/关闭 Windows 输入法（TSF）。</summary>
    private void RequestImeClient(TextInputMethodClient? client)
    {
        var args = new TextInputMethodClientRequestedEventArgs
        {
            Client = client,
            RoutedEvent = InputElement.TextInputMethodClientRequestedEvent,
        };
        RaiseEvent(args);
    }
}
