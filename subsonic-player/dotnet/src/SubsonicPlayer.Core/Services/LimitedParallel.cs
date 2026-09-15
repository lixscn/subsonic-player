using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace SubsonicPlayer.Services;

/// <summary>
/// 限并发并行执行：把「串行 N+1 请求」压成几轮并行。
///
/// <para>为什么是「限并发」而不是无脑 <c>Task.WhenAll</c>：服务端是窄连接池（SQLAlchemy），
/// 工程里已经吃过一次「并发太多把连接池打满、请求全部悬挂」的亏 —— <c>GrowFlatSongsAsync</c>
/// 的专辑展开就是限 6 并发。所以默认值取 6。</para>
///
/// <para>典型收益：智能推荐原本是 ~32 个**串行**请求（收藏/歌单 3 + 6 位艺术家专辑列表 6
/// + 专辑详情 ≤12 + 随机歌曲 11），这台服务器 0.75s/请求 ⇒ 25 秒；限 6 并发后只剩 5~7 轮。</para>
/// </summary>
public static class LimitedParallel
{
    /// <summary>默认并发度。与服务端连接池相容的经验值（见工程内 GrowFlatSongsAsync / WhenAllLimitedAsync）。</summary>
    public const int DefaultConcurrency = 6;

    /// <summary>对 source 逐项执行 selector，最多同时 maxConcurrency 个；返回**与输入同序**的结果。</summary>
    public static async Task<TResult[]> SelectAsync<TSource, TResult>(
        IEnumerable<TSource> source,
        Func<TSource, Task<TResult>> selector,
        int maxConcurrency = DefaultConcurrency)
    {
        var items = source as IList<TSource> ?? source.ToList();
        var results = new TResult[items.Count];
        if (items.Count == 0)
            return results;

        using var gate = new SemaphoreSlim(Math.Max(1, maxConcurrency));
        var tasks = new Task[items.Count];

        for (var i = 0; i < items.Count; i++)
            tasks[i] = RunOneAsync(i);

        await Task.WhenAll(tasks);
        return results;

        async Task RunOneAsync(int index)
        {
            await gate.WaitAsync();
            try
            {
                results[index] = await selector(items[index]);
            }
            finally
            {
                gate.Release();
            }
        }
    }

    /// <summary>只关心副作用、不收集结果的版本。</summary>
    public static Task ForEachAsync<TSource>(
        IEnumerable<TSource> source,
        Func<TSource, Task> action,
        int maxConcurrency = DefaultConcurrency)
        => SelectAsync(source, async item =>
        {
            await action(item);
            return true;
        }, maxConcurrency);
}
