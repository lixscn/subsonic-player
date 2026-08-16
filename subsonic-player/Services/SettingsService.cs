using System.IO;
using System.Text.Json;
using System.Threading.Tasks;
using SubsonicPlayer.Models;

namespace SubsonicPlayer.Services;

/// <summary>配置持久化（JSON 文件，位于数据目录）。</summary>
public class SettingsService
{
    private readonly string _filePath;
    private static readonly JsonSerializerOptions JsonOpts = new() { WriteIndented = true };

    public AppSettings Settings { get; private set; }

    public SettingsService(string dataDir)
    {
        Directory.CreateDirectory(dataDir);
        _filePath = Path.Combine(dataDir, "settings.json");
        Settings = Load();
    }

    public async Task SaveAsync()
    {
        var json = JsonSerializer.Serialize(Settings, JsonOpts);
        await File.WriteAllTextAsync(_filePath, json);
    }

    private AppSettings Load()
    {
        try
        {
            if (File.Exists(_filePath))
            {
                var json = File.ReadAllText(_filePath);
                return JsonSerializer.Deserialize<AppSettings>(json) ?? new AppSettings();
            }
        }
        catch
        {
            // 配置损坏时回退空配置
        }

        return new AppSettings();
    }
}
