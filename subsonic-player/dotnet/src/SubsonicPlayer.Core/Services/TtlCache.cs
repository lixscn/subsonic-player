using System;
using System.Collections.Concurrent;

namespace SubsonicPlayer.Services;

/// <summary>短时进程内缓存（TTL 到点自动失效），用于避免慢网下重复拉取列表数据。</summary>
public sealed class TtlCache<TKey, TValue> where TKey : notnull
{
    private readonly TimeSpan _ttl;
    private readonly ConcurrentDictionary<TKey, (TValue Value, DateTime Expires)> _map = new();

    public TtlCache(TimeSpan ttl) => _ttl = ttl;

    public bool TryGet(TKey key, out TValue value)
    {
        if (_map.TryGetValue(key, out var e))
        {
            if (DateTime.UtcNow < e.Expires)
            {
                value = e.Value;
                return true;
            }
            _map.TryRemove(key, out _);
        }
        value = default!;
        return false;
    }

    public void Set(TKey key, TValue value)
        => _map[key] = (value, DateTime.UtcNow + _ttl);
}
