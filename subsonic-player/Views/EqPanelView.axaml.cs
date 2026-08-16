using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Platform.Storage;
using SubsonicPlayer.ViewModels;

namespace SubsonicPlayer.Views;

public partial class EqPanelView : UserControl
{
    public EqPanelView()
    {
        InitializeComponent();
    }

    private async void OnExportClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not EqPanelViewModel vm)
            return;

        var top = TopLevel.GetTopLevel(this);
        if (top is null)
            return;

        var file = await top.StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
        {
            SuggestedFileName = "eq-preset.json",
            DefaultExtension = "json",
        });
        if (file is null)
            return;

        await vm.ExportAsync(file.Path.LocalPath);
    }

    private async void OnImportClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not EqPanelViewModel vm)
            return;

        var top = TopLevel.GetTopLevel(this);
        if (top is null)
            return;

        var files = await top.StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            AllowMultiple = false,
            FileTypeFilter = new[] { new FilePickerFileType("JSON") { Patterns = new[] { "*.json" } } },
        });
        if (files.Count == 0)
            return;

        await vm.ImportAsync(files[0].Path.LocalPath);
    }
}
