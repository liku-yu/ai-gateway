-- 结算：余额差额 + 日/月预算累计，合并为「一次 Redis 往返」
--
-- 原先 settle 需要 1 次 adjust + 2 次 INCRBY + 2 次 EXPIRE = 5 次往返，
-- 合并后仅 1 次。EXPIRE 只在键首次创建时设置，避免每次请求都续期（省 2 次命令）。
--
-- KEYS[1] = 余额 key        （不存在则按 baseline 初始化）
-- KEYS[2] = 日预算 key
-- KEYS[3] = 月预算 key
-- ARGV[1] = deltaFen  正数=退还，负数=补扣
-- ARGV[2] = costFen   本次实际成本（计入预算；<=0 则不累加）
-- ARGV[3] = dayTtlSec
-- ARGV[4] = monthTtlSec
-- ARGV[5] = baselineFen 余额键缺失时的初始值
-- 返回：{操作后余额, 日累计, 月累计}

local balanceKey = KEYS[1]
local dayKey = KEYS[2]
local monthKey = KEYS[3]
local delta = tonumber(ARGV[1])
local cost = tonumber(ARGV[2])
local dayTtl = tonumber(ARGV[3])
local monthTtl = tonumber(ARGV[4])
local baseline = tonumber(ARGV[5])

-- 余额：缺失时按基线初始化，再做差额调整
local balance = tonumber(redis.call('GET', balanceKey))
if balance == nil then
    balance = baseline
end
balance = balance + delta
redis.call('SET', balanceKey, balance, 'KEEPTTL')

-- 预算累计：键首次出现时才设置 TTL
local dayUsed = 0
local monthUsed = 0
if cost > 0 then
    dayUsed = redis.call('INCRBY', dayKey, cost)
    if dayUsed == cost then
        redis.call('EXPIRE', dayKey, dayTtl)
    end
    monthUsed = redis.call('INCRBY', monthKey, cost)
    if monthUsed == cost then
        redis.call('EXPIRE', monthKey, monthTtl)
    end
else
    dayUsed = tonumber(redis.call('GET', dayKey) or '0')
    monthUsed = tonumber(redis.call('GET', monthKey) or '0')
end

return {balance, dayUsed, monthUsed}
