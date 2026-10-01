-- 并发计数（带 TTL 兜底，防止客户端异常退出导致计数泄漏）
-- KEYS[1] = 并发 key
-- ARGV[1] = 并发上限
-- ARGV[2] = TTL 秒（兜底）
-- 返回：{是否放行(1/0), 当前并发数}
local key   = KEYS[1]
local limit = tonumber(ARGV[1])
local ttl   = tonumber(ARGV[2])

local current = tonumber(redis.call('GET', key) or '0')
if current >= limit then
    return {0, current}
end

current = redis.call('INCR', key)
redis.call('EXPIRE', key, ttl)
return {1, current}
