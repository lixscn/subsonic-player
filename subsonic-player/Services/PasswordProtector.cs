using System;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace SubsonicPlayer.Services;

/// <summary>
/// 使用 Windows DPAPI（CryptProtectData）对密码进行加密存储，密钥由当前用户凭据托管，无需自管密钥。
/// 序列化到 settings.json 时以 "enc:" 前缀的 Base64 存储；旧明文配置自动兼容读取。
/// </summary>
public static class PasswordProtector
{
    private const string Prefix = "enc:";
    private const int CryptoProtectUiForbidden = 0x1;

    /// <summary>加密明文（空值原样返回；加密失败回退明文）。</summary>
    public static string Protect(string plain)
    {
        if (string.IsNullOrEmpty(plain))
            return plain;

        try
        {
            var plainBytes = Encoding.UTF8.GetBytes(plain);
            var inBlob = new DATA_BLOB { cbData = plainBytes.Length, pbData = Marshal.AllocHGlobal(plainBytes.Length) };
            var outBlob = new DATA_BLOB();
            var entropy = new DATA_BLOB();
            try
            {
                Marshal.Copy(plainBytes, 0, inBlob.pbData, plainBytes.Length);
                if (!CryptProtectData(ref inBlob, "SubsonicPlayer Password", ref entropy, IntPtr.Zero, IntPtr.Zero, CryptoProtectUiForbidden, ref outBlob))
                    return plain;

                var outBytes = new byte[outBlob.cbData];
                Marshal.Copy(outBlob.pbData, outBytes, 0, outBlob.cbData);
                return Prefix + Convert.ToBase64String(outBytes);
            }
            finally
            {
                if (inBlob.pbData != IntPtr.Zero) Marshal.FreeHGlobal(inBlob.pbData);
                if (outBlob.pbData != IntPtr.Zero) LocalFree(outBlob.pbData);
            }
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
            var protectedBytes = Convert.FromBase64String(stored[Prefix.Length..]);
            var inBlob = new DATA_BLOB { cbData = protectedBytes.Length, pbData = Marshal.AllocHGlobal(protectedBytes.Length) };
            var outBlob = new DATA_BLOB();
            var entropy = new DATA_BLOB();
            try
            {
                Marshal.Copy(protectedBytes, 0, inBlob.pbData, protectedBytes.Length);
                if (!CryptUnprotectData(ref inBlob, null, ref entropy, IntPtr.Zero, IntPtr.Zero, CryptoProtectUiForbidden, ref outBlob))
                    return "";

                var outBytes = new byte[outBlob.cbData];
                Marshal.Copy(outBlob.pbData, outBytes, 0, outBlob.cbData);
                return Encoding.UTF8.GetString(outBytes);
            }
            finally
            {
                if (inBlob.pbData != IntPtr.Zero) Marshal.FreeHGlobal(inBlob.pbData);
                if (outBlob.pbData != IntPtr.Zero) LocalFree(outBlob.pbData);
            }
        }
        catch
        {
            return "";
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DATA_BLOB
    {
        public int cbData;
        public IntPtr pbData;
    }

    [DllImport("crypt32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CryptProtectData(
        ref DATA_BLOB pDataIn,
        string? szDataDescr,
        ref DATA_BLOB pOptionalEntropy,
        IntPtr pvReserved,
        IntPtr pPromptStruct,
        int dwFlags,
        ref DATA_BLOB pDataOut);

    [DllImport("crypt32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CryptUnprotectData(
        ref DATA_BLOB pDataIn,
        string? szDataDescr,
        ref DATA_BLOB pOptionalEntropy,
        IntPtr pvReserved,
        IntPtr pPromptStruct,
        int dwFlags,
        ref DATA_BLOB pDataOut);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr LocalFree(IntPtr hMem);
}

/// <summary>密码字段的 JSON 转换器：写时加密、读时解密，业务代码无感知。</summary>
public class EncryptedPasswordConverter : JsonConverter<string>
{
    public override string Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        => PasswordProtector.Unprotect(reader.GetString() ?? "");

    public override void Write(Utf8JsonWriter writer, string value, JsonSerializerOptions options)
        => writer.WriteStringValue(PasswordProtector.Protect(value));
}
