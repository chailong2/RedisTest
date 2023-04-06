package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final ExecutorService CHACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);//创建线程池，里面10个线程

    public void setWithTTLExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    private boolean tryLock(String key) //模拟锁
    {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);//setIfAbsent是redis汇总的setnx命令
//setnx只有当key不存在时才能set，这里模拟锁的原理就是，如果一个人setnx调用后就设置了值，其它再Setnx就无法setnx了，这样就达到了一个互斥的效果
        //释放锁时只需把setnx设置的数据删除即可
        return BooleanUtil.isTrue(flag);
    }

    public <ID, R> void saveShop2Redis(ID id, int expireSecond, Function<ID, R> dbFallback, Class<R> type, String shopprefix) throws InterruptedException //这个函数用来添加热点Key
    {
        //查询店铺信息
        R r = dbFallback.apply(id);
        Thread.sleep(200); //模拟缓存重建的延迟
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(r);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSecond));
        //写入redis
        stringRedisTemplate.opsForValue().set(shopprefix + id, JSONUtil.toJsonStr(redisData));
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    //通过给Redis加空对象来解决缓存穿透问题
    public <R, ID> R GetWithTTLExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {  //这里由于不确定操作对象类型，所以这里使用到来范型，Class<R>type的作用是确定操作对象类型
        //TODO 1 从redis中查询商铺缓存
        String key = keyPrefix + id;
        String Json = stringRedisTemplate.opsForValue().get(key);
        System.out.println(Json);
        System.out.println(JSONUtil.toBean(Json, type));
        //TODO 2 查询数据是否存在
        if (StrUtil.isNotBlank(Json)) {
            //TODO 3 存在则直接向客户端返回数据
            return JSONUtil.toBean(Json, type);//将Json字符串转换为实体类对象
        }
        //isNotBlank将""作为false，我们已经将reids缓存中加入了空对象，如果还查数据库就出现来缓存穿透的问题，所以为了解决缓存穿透问题，我们需要再次判断空
        if (Json != null) {
            //返回一个空值
            return null;
        }
        //由于我们不知道操作对象具体操作数据库的函数，所以这里用到来范式，Function<ID,R> dbFallback，ID为参数，R为返回值类型
        R r = dbFallback.apply(id);
        //TODO 4 不存在则查询数据库
        if (r == null) {
            //TODO 5.1 将空值写入Redis
            this.setWithTTLExpire(key, "", time, timeUnit);
            //TODO 5.2 返回错误信息
            return null;
        }
        //TODO 6 数据库中存在先写入Redis
        this.setWithTTLExpire(key, r, time, timeUnit);
        //TODO 7 数据返回给客户
        return r;
    }

    //用逻辑过期时间解决缓存击穿问题
    public <R, ID> R GetWithLogicalExpire(String keyPrefix, String lockPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
//        //TODO 1 从redis中查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
//        //TODO 2 查询数据是否存在
        if (StrUtil.isBlank(Json)) {
            //TODO 2.1 不存在则直接向客户端返回数据
            return null;
        }
//        //TODO 2.2 存在判断数据逻辑上是否过期
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();  //上一句代码只是将redisdata做了反序列化，而redisdata里面还有一个Shop对象没有反序列化，下面同样要序列化
        R r = JSONUtil.toBean(Json, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期直接返回数据
            return r;
        }
//        //TODO 4 数据逻辑过期了需要缓存重建
//        //TODO 4.1 获取互斥锁
        String lockkey = lockPrefix + id;
        if (tryLock(lockkey)) {  //获取成功，开启独立线程开始缓存重建
            CHACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20, dbFallback, type, keyPrefix);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockkey);  //释放锁
                }
//
//            });//lambda表达式
            });
//
        }
        //TODO 7 数据返回给客户
        return r;
    }
    public <R,ID> R GetWithMutex(ID id,String keyPrefix,String lockPrefix,Class<R> type,Function<ID, R> dbFallback) { //解决缓存击穿问题的代码，用互斥锁解决
        //TODO 1 从redis中查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get( keyPrefix+ id);
        //TODO 2 查询数据是否存在
        if (StrUtil.isNotBlank(Json)) {
            //TODO 3 存在则直接向客户端返回数据
            R r = JSONUtil.toBean(Json, type);//将Json字符串转换为实体类对象
            return r;
        }
        //isNotBlank将""作为false，我们已经将reids缓存中加入了空对象，如果还查数据库就出现来缓存穿透的问题，所以为了解决缓存穿透问题，我们需要再次判断空
        if (Json!=null)
        {
            //返回一个错误信息
            return  null;
        }

        //TODO 4 不存在则查询数据库（这里开始实现缓存重建）
        //TODO 4.1 获取互斥锁
        String lockey=lockPrefix+id;
        R r = null;
        try {
            boolean islock = tryLock(lockey);
            //TODO 4.2 判断锁获取成功则开始重建
            if (!islock) {
                Thread.sleep(50);  //获取失败线程休眠
                return GetWithMutex(id,keyPrefix,lockPrefix,type,dbFallback); //递归
            }
            r = dbFallback.apply(id);
            Thread.sleep(200); //模拟重建数据库的延迟
            if (r == null) {
                //将空值写入Redis
                stringRedisTemplate.opsForValue().set(keyPrefix+id,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                //返回错误信息
              return  null;
            }
            stringRedisTemplate.opsForValue().set(keyPrefix+id,JSONUtil.toJsonStr(r),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            //TODO 4.3 判断锁获取失败则线程休眠并重试

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //TODO 5 释放互斥锁
            unlock(lockey);
        }
        //TODO 7 数据返回给客户
        return r;
    }
}
