package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private String name;  //锁名称
    private static final String key_prefix="lock:";
    private static final String ID_Prefix= UUID.randomUUID().toString(true)+"-";
    StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {  //脚本初始化
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取当前线程的ID
        String id = ID_Prefix+Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key_prefix + name, id, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);//避免拆箱
    }

    @Override
    public void unlock() {
//        //获得当前线程的锁的名称
//        String id = ID_Prefix+Thread.currentThread().getId();
//        //获得当前锁的名称
//        String nowlock = stringRedisTemplate.opsForValue().get(key_prefix + name);
//        if (id.equals(nowlock)) {
//            stringRedisTemplate.delete(key_prefix+name);

        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(key_prefix+name),
                ID_Prefix+Thread.currentThread().getId()
        );

        }
}
