@echo off
chcp 65001 >nul
rem ============================================================
rem  publish-single.bat — 运行单文件打包脚本（部署到 D:\tools\SubsonicPlayer）
rem ============================================================
setlocal
cd /d %~dp0

rem 用 Windows PowerShell 执行脚本
powershell -ExecutionPolicy Bypass -File publish-single.ps1
if errorlevel 1 (
    echo.
    echo [失败] 打包或部署出错，请查看上方日志。
    pause
    exit /b 1
)

echo.
echo [成功] 单文件已打包并部署。
pause
