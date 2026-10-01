-- 准入控制：限流 + 余额预扣 + 预算校验，合并为「一次 Redis 往返」
--
-- 为什么合并：逐项校验会产生 7~14 次串行 Redis 往返，Redis 的单线程特性会把
--           这些往返串行化，成为网关吞吐的硬天花板（实测 ~750 req/s 上不去）。
--           合并后单请求只需 1 次往返，吞吐提升数倍。
--
-- 两阶段语义（务必保持）：
--   阶段一 只读校验全部条件，任一项不满足立即返回失败，**不做任何扣减**；
--   阶段二 全部通过后才统一扣减。
-- 保证「要么全放行并全额扣减，要么完全不动」，不会出现部分扣减的脏状态。
--
-- ARGV 编码：
--   ARGV[1] = 规格数量 n
--   随后每 5 个一组：type, key, p1, p2, p3
--     type='1' 滑动窗口  : p1=limit, p2=windowMs, p3=0        (member 用时间戳+序号)
--     type='2' 令牌桶    : p1=capacity, p2=refillPerSec, p3=cost
--     type='3' 并发计数  : p1=limit, p2=ttlSec, p3=0
--     type='4' 余额预扣  : p1=amountFen（校验后 DECRBY）
--     type='5' 预算校验  : p1=limitFen（只读比较，超出即拒绝）
--   ARGV[n*5+2] = 当前时间戳(ms)
--
-- 返回：{allowed(1/0), 失败规格下标(1-based，0=全通过), 建议等待毫秒}

local n = tonumber(ARGV[1])
local base = 2
local now = tonumber(ARGV[n * 5 + base])

local function spec(i)
    local o = base + (i - 1) * 5
    return ARGV[o], ARGV[o + 1], tonumber(ARGV[o + 2]), tonumber(ARGV[o + 3]), tonumber(ARGV[o + 4])
end

-- ==================== 阶段一：只读校验 ====================
for i = 1, n do
    local stype, key, p1, p2, p3 = spec(i)

    if stype == '1' then
        redis.call('ZREMRANGEBYSCORE', key, 0, now - p2)
        local count = redis.call('ZCARD', key)
        if count >= p1 then
            local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
            local wait = p2
            if oldest[2] then
                wait = math.max(1, p2 - (now - tonumber(oldest[2])))
            end
            return {0, i, wait}
        end
    elseif stype == '2' then
        -- 桶不存在 = 首次使用，视为满桶（绝不能当成拒绝，否则新应用第一个请求就被限流）
        local data = redis.call('HMGET', key, 'tokens', 'ts')
        local tokens = tonumber(data[1])
        local ts = tonumber(data[2])
        local avail
        if tokens == nil then
            avail = p1
        else
            avail = math.min(p1, tokens + math.max(0, now - ts) / 1000.0 * p2)
        end
        if avail < p3 then
            -- p2 为 0 时无法估算等待时间，返回 1s 兜底，避免除零
            if p2 <= 0 then
                return {0, i, 1000}
            end
            return {0, i, math.ceil((p3 - avail) / p2 * 1000)}
        end
    elseif stype == '3' then
        if tonumber(redis.call('GET', key) or '0') >= p1 then
            return {0, i, 1000}
        end
    elseif stype == '4' then
        -- 余额键缺失视为 0（未充值），直接拒绝而不是静默放行
        local balance = tonumber(redis.call('GET', key) or '0')
        if balance < p1 then
            return {0, i, 0}
        end
    elseif stype == '5' then
        local used = tonumber(redis.call('GET', key) or '0')
        if used >= p1 then
            return {0, i, 0}
        end
    end
end

-- ==================== 阶段二：统一扣减 ====================
for i = 1, n do
    local stype, key, p1, p2, p3 = spec(i)

    if stype == '1' then
        redis.call('ZADD', key, now, tostring(now) .. '-' .. i)
        redis.call('PEXPIRE', key, p2)
    elseif stype == '2' then
        local data = redis.call('HMGET', key, 'tokens', 'ts')
        local tokens = tonumber(data[1])
        local ts = tonumber(data[2])
        local avail
        if tokens == nil then
            avail = p1 - p3
        else
            avail = math.min(p1, tokens + math.max(0, now - ts) / 1000.0 * p2) - p3
        end
        redis.call('HSET', key, 'tokens', avail, 'ts', now)
        redis.call('PEXPIRE', key, p2 > 0 and math.max(1000, math.ceil(p1 / p2 * 1000)) or 60000)
    elseif stype == '3' then
        redis.call('INCR', key)
        redis.call('EXPIRE', key, p2)
    elseif stype == '4' then
        redis.call('DECRBY', key, p1)
    end
    -- type='5' 预算校验为只读，无需扣减
end

return {1, 0, 0}
