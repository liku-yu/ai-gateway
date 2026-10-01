#!/usr/bin/env python3
"""
AI 网关压测脚本（测「网关自身开销」，不含真实上游延迟）。

方法：用一个零延迟的本地 mock 上游作为对照基准，先单独测 mock 的直连延迟，
      再测经网关的端到端延迟，两者之差即网关引入的额外开销。

为什么必须支持多进程（--procs）：
    asyncio/aiohttp 是单线程事件循环，单进程的客户端自身吞吐上限约 1~1.5k req/s
    （实测并发 1 时单请求 2.7ms → 天花板约 370 req/s；并发升高后受 GIL 限制）。
    单进程压测会把「客户端的瓶颈」误读成「网关的瓶颈」，因此默认按并发自适应
    拆分到多个进程，父进程只负责汇总，这样量出来才是网关的真实吞吐。

    同样的坑也存在于 `ab`：它是单线程工具，且默认对响应体长度做一致性校验，
    而网关响应里的 latencyMs 位数会变化（5 与 10 字节数不同），必须加 -l 才能消除
    误报的 "Failed requests (Length)"。

用法：
  python3 scripts/bench.py --url http://127.0.0.1:8080/v1/chat/completions \
      --key sk-gw-bench-0001 --model chat-default --duration 15 --concurrency 64
  # 直连 mock 作为对照基准
  python3 scripts/bench.py --url http://127.0.0.1:18081/v1/chat/completions --duration 15 --concurrency 64
"""
import argparse
import asyncio
import json
import statistics
import time
from collections import Counter
from multiprocessing import Process, Queue

import aiohttp

PAYLOAD = {
    "model": "chat-default",
    "messages": [{"role": "user", "content": "压测请求，请简短回复"}],
    "max_tokens": 16,
    "extra_body": {"masking": {"enabled": False}},
}


async def worker(session, url, headers, payload, deadline, latencies, errors):
    while time.perf_counter() < deadline:
        start = time.perf_counter()
        try:
            async with session.post(url, json=payload, headers=headers) as resp:
                await resp.read()
                elapsed = (time.perf_counter() - start) * 1000
                if resp.status == 200:
                    latencies.append(elapsed)
                else:
                    errors.append(resp.status)
        except Exception as exc:  # 连接错误、超时等
            errors.append(type(exc).__name__)


async def run_async(args, concurrency, latencies, errors):
    payload = dict(PAYLOAD)
    payload["model"] = args.model
    headers = {"Authorization": f"Bearer {args.key}", "Content-Type": "application/json"}

    connector = aiohttp.TCPConnector(limit=concurrency * 2, force_close=False)
    timeout = aiohttp.ClientTimeout(total=30)

    async with aiohttp.ClientSession(connector=connector, timeout=timeout) as session:
        # 预热：让连接池、Redis 连接、JIT 都热起来，避免把冷启动算进延迟
        for _ in range(min(20, concurrency)):
            try:
                async with session.post(args.url, json=payload, headers=headers) as r:
                    await r.read()
            except Exception:
                pass

        deadline = time.perf_counter() + args.duration
        await asyncio.gather(*[
            worker(session, args.url, headers, payload, deadline, latencies, errors)
            for _ in range(concurrency)
        ])


def _proc_main(args, concurrency, queue):
    latencies, errors = [], []
    start = time.perf_counter()
    try:
        asyncio.run(run_async(args, concurrency, latencies, errors))
    except Exception as exc:
        errors.append(type(exc).__name__)
    queue.put((latencies, errors, time.perf_counter() - start))


def percentile(values, pct):
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, int(len(ordered) * pct))
    return ordered[idx]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:8080/v1/chat/completions")
    ap.add_argument("--key", default="sk-gw-bench-0001")
    ap.add_argument("--model", default="chat-default")
    ap.add_argument("--duration", type=float, default=15)
    ap.add_argument("--concurrency", type=int, default=64)
    ap.add_argument("--procs", type=int, default=0, help="0 = 按并发自动拆分（最多 4 个）")
    ap.add_argument("--label", default="gateway")
    args = ap.parse_args()

    procs = args.procs or max(1, min(4, args.concurrency // 16 or 1))
    procs = min(procs, args.concurrency)
    per_conc = max(1, args.concurrency // procs)

    queue: Queue = Queue()
    workers = [Process(target=_proc_main, args=(args, per_conc, queue)) for _ in range(procs)]

    wall_start = time.perf_counter()
    for p in workers:
        p.start()

    latencies, errors = [], []
    proc_walls = []
    for _ in workers:
        pl, pe, pw = queue.get()
        latencies.extend(pl)
        errors.extend(pe)
        proc_walls.append(pw)
    for p in workers:
        p.join()
    wall = time.perf_counter() - wall_start

    if not latencies:
        print(f"[{args.label}] 全部失败，错误样本: {errors[:5]}")
        return

    ok = len(latencies)
    total = ok + len(errors)
    print(f"===== {args.label} =====")
    print(f"并发={args.concurrency}(进程={procs}×{per_conc}) 时长={wall:.1f}s "
          f"总请求={total} 成功={ok} 失败={len(errors)}")
    print(f"吞吐: {ok / wall:.0f} req/s")
    print(f"延迟(ms): 平均={statistics.mean(latencies):.2f} "
          f"P50={percentile(latencies, 0.50):.2f} "
          f"P90={percentile(latencies, 0.90):.2f} "
          f"P95={percentile(latencies, 0.95):.2f} "
          f"P99={percentile(latencies, 0.99):.2f} "
          f"max={max(latencies):.2f}")
    if errors:
        print(f"错误分布: {dict(Counter(errors))}")


if __name__ == "__main__":
    main()
