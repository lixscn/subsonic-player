using System;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace SubsonicPlayer.Services;

/// <summary>
/// 密码加解密入口：委托给 <see cref="AppServices.SecretProtector"/>（跨平台）。
/// Windows 启动时注入 DPAPI 实现（Desktop 层），其他平台用 AES-GCM 兜底。
/// 序列化到 settings.json 时以 "enc:" 前缀的 Base64 存储；旧明文配置自动兼容读取。
/// </summary>
public static class PasswordProtector
{
    private const string Prefix = "enc:";

    /// <summary>加密明文（空值原样返回；加密失败回退明文）。</summary>
    public static string Protect(string plain)
    {
        if (string.IsNullOrEmpty(plain))
            return plain;

        try
        {
            return Prefix + AppServices.SecretProtector.Protect(plain);
        }
        catch
        {
            return plain;
        }
    }

    /// <summary>解密存储值（旧明文或无前缀原样返回；解密失败返回空）。</summary>
    public static string Unprotect(string stored)
    {
        if (string.IsNullOrEmpty(stored))
            return stored;

        if (!stored.StartsWith(Prefix, StringComparison.Ordinal))
            return stored;

        try
        {
            return AppServices.SecretProtector.Unprotect(stored[Prefix.Length..]) ?? "";
        }
        catch
        {
            return "";
        }
    }
}

/// <summary>密码字段的 JSON 转换器：写时加密、读时解密，业务代码无感知。</summary>
public class EncryptedPasswordConverter : JsonConverter<string>
{
    public override string Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        => PasswordProtector.Unprotect(reader.GetString() ?? "");

    public override void Write(Utf8JsonWriter writer, string value, JsonSerializerOptions options)
        => writer.WriteStringValue(PasswordProtector.Protect(value));
}
