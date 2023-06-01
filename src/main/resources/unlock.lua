---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by xe85i.
--- DateTime: 2023/5/31 20:43
---
-- 锁的key
local key = KEYS[1]
-- 当前线程ID
local threadID = ARGV[1]
-- 获取锁的value（线程ID）
local id = redis.call("get", key)

if(id == threadID) then
    return redis.call("del", key)
end
return 0