--Lua脚本实现上面流程
--获取锁中的线程标示
local id=redis.call('get',KEYS[1])
--比较线程标示与锁中的标示是否一致
if(id==ARGV[1]) then
    -- 释放锁
    return redis.call('del',KEYS[1])
end
return 0