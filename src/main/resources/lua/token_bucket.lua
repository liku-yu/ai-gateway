-- 令牌桶（TPM / 按 token 计费型限流）
-- KEYS[1] = 桶 key（hash: tokens / ts）
-- ARGV[1] = 桶容量 capacity
-- ARGV[2] = 每秒补充速率 refill_per_sec
-- ARGV[3] = 本次消耗量 cost
-- ARGV[4] = 当前时间戳（毫秒）
-- 返回：{是否放行(1/0), 剩余令牌, 需等待毫秒数}
local key      = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill   = tonumber(ARGV[2])
local cost     = tonumber(ARGV[3])
local now      = tonumber(ARGV[4])

local data = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts     = tonumber(data[2])

if tokens == nil then
    tokens = capacity
    ts = now
end

-- 按经过时间补充令牌（上限为容量）
local delta = math.max(0, now - ts) / 1000.0
tokens = math.min(capacity, tokens + delta * refill)

if tokens < cost then
    local deficit = cost - tokens
    local wait = math.ceil(deficit / refill * 1000)
    redis.call('HSET', key, 'tokens', tokens, 'ts', now)
    redis.call('PEXPIRE', key, math.max(1000, math.ceil(capacity / refill * 1000)))
    return {0, math.floor(tokens), wait}
end

tokens = tokens - cost
redis.call('HSET', key, 'tokens', tokens, 'ts', now)
redis.call('PEXPIRE', key, math.max(1000, math.ceil(capacity / refill * 1000)))
return {1, math.floor(tokens), 0}
