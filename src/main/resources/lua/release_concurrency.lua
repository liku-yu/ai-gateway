-- 释放并发占用（只减不出现负数）
-- KEYS[1] = 并发 key
-- 返回：释放后的并发数
local key = KEYS[1]
local current = tonumber(redis.call('GET', key) or '0')
if current <= 0 then
    return 0
end
return redis.call('DECR', key)
