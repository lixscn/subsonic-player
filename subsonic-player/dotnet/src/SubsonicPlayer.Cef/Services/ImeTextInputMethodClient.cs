using System;
using Avalonia;
using Avalonia.Input.TextInput;
using Xilium.CefGlue;

namespace SubsonicPlayer.Services;

/// <summary>
/// 把 Avalonia 输入的 IME 组合（中文/日文/韩文输入法）转发到 CEF OSR 的浏览器 host。
/// CefGlue.Avalonia 默认 <b>没有</b> 实现 IME client，Avalonia 因此从不把组合事件路由给 CEF，
/// 导致搜索框里用不了输入法（只能打英语/数字）。这里按 Avalonia 文档的方式：
/// 当一个 TextInputMethodClient 被 SetClient 挂到顶层后，Avalonia 的 Windows
/// TSF 输入法会被激活，组合文本走 <c>SetPreeditText</c>，提交文本走控件自身的 <c>TextInput</c> 事件。
/// </summary>
internal sealed class ImeTextInputMethodClient : TextInputMethodClient
{
    private readonly Func<CefBrowserHost?> _getHost;
    private readonly Visual _textViewVisual;

    public ImeTextInputMethodClient(Func<CefBrowserHost?> getHost, Visual textViewVisual)
    {
        _getHost = getHost;
        _textViewVisual = textViewVisual;
    }

    public override bool SupportsPreedit => true;

    public override bool SupportsSurroundingText => false;

    public override string SurroundingText => string.Empty;

    public override Visual TextViewVisual => _textViewVisual;

    // 组合窗口（候选词）位置：OSR 下 CEF 把页面合成到整块控件上，这里给一个默认矩形。
    // 如需精确定位可在拿到 CEF ImeCompositionRange 后更新并 RaiseCursorRectangleChanged()。
    public override Rect CursorRectangle => new Rect();

    public override TextSelection Selection { get; set; }

    public override void SetPreeditText(string? preeditText)
    {
        SetComposition(preeditText);
    }

    public override void SetPreeditText(string? preeditText, int? cursorOffset)
    {
        SetComposition(preeditText);
    }

    private void SetComposition(string? preeditText)
    {
        var host = _getHost();
        if (host is null)
        {
            return;
        }

        if (string.IsNullOrEmpty(preeditText))
        {
            host.ImeCancelComposition();
            return;
        }

        try
        {
            // 组合中的拼音给整段下划线（Solid），让 CEF 聚焦输入框显示未确认文本。
            var range = new CefRange(0, preeditText.Length);
            var underline = new CefCompositionUnderline
            {
                Range = range,
                Thick = true,
                Style = CefCompositionUnderlineStyle.Solid,
                Color = new CefColor(0, 0, 0, 0), // 透明底，仅下划线
            };
            host.ImeSetComposition(preeditText, 1, underline, range, range);
        }
        catch
        {
            // 下划线渲染是可选增强：即使 CefGlue 绑定对这个重载支持不佳，也不阻断后面 TextInput 的提交。
        }
    }

    protected override void RequestReset() => _getHost()?.ImeCancelComposition();
}
