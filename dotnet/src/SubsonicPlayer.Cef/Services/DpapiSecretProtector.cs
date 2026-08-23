using System;
using System.Runtime.InteropServices;
using System.Text;
using SubsonicPlayer.Services;

namespace SubsonicPlayer;

/// <summary>
/// Windows DPAPI 密码加密实现（CryptProtectData），密钥由当前用户凭据托管。
/// 纯 crypt32 P/Invoke，不依赖 Windows 目标框架；按运行时操作系统注入
/// <see cref="AppServices.SecretProtector"/>（Windows 上任一 TFM 均可用）。
/// </summary>
public sealed class DpapiSecretProtector : ISecretProtector
{
    private const int CryptoProtectUiForbidden = 0x1;

    public string Protect(string plain)
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
            return Convert.ToBase64String(outBytes);
        }
        finally
        {
            if (inBlob.pbData != IntPtr.Zero) Marshal.FreeHGlobal(inBlob.pbData);
            if (outBlob.pbData != IntPtr.Zero) LocalFree(outBlob.pbData);
        }
    }

    public string Unprotect(string stored)
    {
        var protectedBytes = Convert.FromBase64String(stored);
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