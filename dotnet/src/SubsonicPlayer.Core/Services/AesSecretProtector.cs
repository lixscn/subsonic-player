using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace SubsonicPlayer.Services;

/// <summary>
/// AES-256-GCM 密码加密兜底实现（macOS/Linux/移动端无 DPAPI 时的通用方案）。
/// 密钥首次运行时生成并存入用户数据目录（0600），此后复用。
/// 安全等级低于 Windows DPAPI / 移动端 Keystore，但无平台依赖、可移植。
/// </summary>
public sealed class AesSecretProtector : ISecretProtector
{
    private const int KeySize = 32;
    private const int NonceSize = 12;
    private const int TagSize = 16;

    private readonly byte[] _key;

    public AesSecretProtector()
    {
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "subsonic-player");
        Directory.CreateDirectory(dir);
        var keyFile = Path.Combine(dir, "secret.key");

        if (File.Exists(keyFile) && new FileInfo(keyFile).Length == KeySize)
        {
            _key = File.ReadAllBytes(keyFile);
        }
        else
        {
            _key = RandomNumberGenerator.GetBytes(KeySize);
            try
            {
                File.WriteAllBytes(keyFile, _key);
#pragma warning disable CA1416
                File.SetUnixFileMode(keyFile, UnixFileMode.UserRead | UnixFileMode.UserWrite);
#pragma warning restore CA1416
            }
            catch
            {
                // 权限设置失败不影响功能
            }
        }
    }

    public string Protect(string plain)
    {
        var plainBytes = Encoding.UTF8.GetBytes(plain);
        var nonce = RandomNumberGenerator.GetBytes(NonceSize);

        using var aes = new AesGcm(_key, TagSize);
        var cipher = new byte[plainBytes.Length];
        var tag = new byte[TagSize];
        aes.Encrypt(nonce, plainBytes, cipher, tag);

        var outBytes = new byte[NonceSize + TagSize + cipher.Length];
        nonce.CopyTo(outBytes, 0);
        tag.CopyTo(outBytes, NonceSize);
        cipher.CopyTo(outBytes, NonceSize + TagSize);
        return Convert.ToBase64String(outBytes);
    }

    public string Unprotect(string stored)
    {
        var all = Convert.FromBase64String(stored);
        if (all.Length < NonceSize + TagSize)
            return "";

        var nonce = new byte[NonceSize];
        var tag = new byte[TagSize];
        var cipher = new byte[all.Length - NonceSize - TagSize];
        Array.Copy(all, 0, nonce, 0, NonceSize);
        Array.Copy(all, NonceSize, tag, 0, TagSize);
        Array.Copy(all, NonceSize + TagSize, cipher, 0, cipher.Length);

        using var aes = new AesGcm(_key, TagSize);
        var plain = new byte[cipher.Length];
        aes.Decrypt(nonce, cipher, tag, plain);
        return Encoding.UTF8.GetString(plain);
    }
}