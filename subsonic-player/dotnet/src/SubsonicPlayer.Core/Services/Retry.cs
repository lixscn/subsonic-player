using System;
using System.Threading.Tasks;

namespace SubsonicPlayer.Services;

/// <summary>轻量失败重试（用于慢网/瞬时失败，避免一次超时就判定失败）。</summary>
public static class Retry
{
    /// <summary>执行 action，失败时重试；全部失败返回 default。适合幂等的小请求（图片/单次 GET）。</summary>
    public static async Task<T?> DoAsync<T>(Func<Task<T>> action, int attempts = 2, int delayMs = 400)
    {
        for (var i = 0; i < attempts; i++)
        {
            try
            {
                return await action();
            }
            catch when (i < attempts - 1)
            {
                await Task.Delay(delayMs);
            }
            catch
            {
                return default;
            }
        }
        return default;
    }
}
