# jniLibs —— 原生库放置目录（此目录由构建脚本读取并打进 APK）

**注意**：本目录下的 `.so` 不进 git（体积大且属第三方），需要按下面清单手工准备。
缺少它们时 App 仍能正常编译运行，只是自动退回系统 MediaPlayer。

## 需要的文件

| 路径 | 来源 | 必需 |
|---|---|---|
| `arm64-v8a/libspbass.so` | `tools/build-native.ps1` 编译产出 | ✅（BASS 路线必需） |
| `arm64-v8a/libbass.so` | BASS 官方 Android 包 | ✅ |
| `arm64-v8a/libbassdsd.so` | BASSDSD 官方 Android 包 | ✅（否则 .dsf 放不了） |
| `arm64-v8a/libbassflac.so` / `libbassape.so` / `libbasswv.so` / `libbassopus.so` / `libbass_fx.so` | 各 add-on 的 Android 包 | 可选（对齐桌面端能力） |
| `x86_64/*` | 同上，x86_64 版本 | 可选（只为在模拟器上验证） |

## 从哪来

1. **BASS 与 add-on**：<https://www.un4seen.com/> → BASS 页面及各 add-on 页面，下载各自的 **Android** 版本。
   压缩包内一般按 ABI 分目录（如 `libs/arm64-v8a/libbass.so`），按上表放到对应 ABI 目录即可。
2. **NDK**（编 `libspbass.so` 用）：
   - Android Studio → SDK Manager → **NDK (Side by side)**，或
   - <https://developer.android.com/ndk/downloads> 下 Windows zip，解压到
     `D:\tools\Android\Sdk\ndk\<版本号>\`（保持这个目录结构，脚本会自动发现）

## 放好之后

```powershell
cd subsonic-player\android
powershell -ExecutionPolicy Bypass -File tools\build-native.ps1   # 编包装层 + 校验各库是否齐
powershell -ExecutionPolicy Bypass -File build.ps1 -Install       # 打包 APK（含 lib/<abi>/*.so）
```

脚本会打印「哪个 ABI、哪个库缺了什么」，不用自己猜。

## 许可提醒

BASS 系列为 Un4seen 产品：**个人非商业使用免费**，商业使用需另行授权。
本项目桌面版已在仓库内分发 BASS 各平台动态库（`dotnet/src/SubsonicPlayer.Cef/native/`），
Android 侧沿用同一策略。公开发布前请确认使用场景符合其许可条款。
