using System;
using System.IO;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;

namespace SubsonicPlayer.Services;

/// <summary>
/// BASS 原生库位于 exe 相邻的 lib\ 目录（发布时由 csproj 将 native\* 拷入 lib\）。
/// DllImport("bass"/"bass_fx"/"bassmix") 默认只搜 exe 目录，搜不到 lib\ 里的库；
/// 这里在程序集加载时（ModuleInitializer）用绝对路径预加载这三个核心库，
/// 之后 DllImport 按模块名即可命中已加载的实例。
/// </summary>
internal static class BassBootstrapper
{
    [ModuleInitializer]
    internal static void Init()
    {
        try
        {
            var libDir = Path.Combine(AppContext.BaseDirectory, "lib");
            if (!Directory.Exists(libDir))
                return;

            // 顺序重要：bassmix / bass_fx 依赖 bass.dll，须先加载 bass。
            foreach (var name in new[] { "bass", "bass_fx", "bassmix" })
            {
                var path = Path.Combine(libDir, name + ".dll");
                if (File.Exists(path))
                    NativeLibrary.Load(path);
            }
        }
        catch
        {
            // 预加载失败不应阻断应用；音频调用会因 DllImport 找不到库而自然报错。
        }
    }
}
