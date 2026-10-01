-- 滑动窗口计数限流（RPM 类）
-- KEYS[1] = 计数 key
-- ARGV[1] = 限流阈值 limit
-- ARGV[2] = 窗口长度（毫秒）
-- ARGV[3] = 当前时间戳（毫秒）
-- ARGV[4] = 本次请求的唯一成员标识
-- 返回：{是否放行(1/0), 当前窗口内计数, 需等待毫秒数}
local key     = KEYS[1]
local limit   = tonumber(ARGV[1])
local window  = tonumber(ARGV[2])
local now     = tonumber(ARGV[3])
local member  = ARGV[4]

-- 清理窗口外的旧记录
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
local count = redis.call('ZCARD', key)

if count >= limit then
    -- 计算最早一条记录过期所需等待时间，供 Retry-After 使用
    local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
    local wait = window
    if oldest[2] then
        wait = math.max(1, window - (now - tonumber(oldest[2])))
    end
    return {0, count, wait}
end

redis.call('ZADD', key, now, member)
redis.call('PEXPIRE', key, window)
return {1, count + 1, 0}
