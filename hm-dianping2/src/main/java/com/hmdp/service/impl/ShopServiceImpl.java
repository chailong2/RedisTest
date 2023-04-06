package com.hmdp.service.impl;

import cn.hutool.cache.Cache;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {
       Shop shop= cacheClient.GetWithTTLExpire(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
       return Result.ok(shop);
    }
//    public Result queryWithMutex(Long id) { //解决缓存击穿问题的代码，用互斥锁解决
//        //TODO 1 从redis中查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
//        //TODO 2 查询数据是否存在
//        if (StrUtil.isNotBlank(shopJson)) {
//            //TODO 3 存在则直接向客户端返回数据
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);//将Json字符串转换为实体类对象
//            return Result.ok(shop);
//        }
//        //isNotBlank将""作为false，我们已经将reids缓存中加入了空对象，如果还查数据库就出现来缓存穿透的问题，所以为了解决缓存穿透问题，我们需要再次判断空
//        if (shopJson!=null)
//        {
//            //返回一个错误信息
//            return  Result.fail("店铺信息不存在！");
//        }
//
//        //TODO 4 不存在则查询数据库（这里开始实现缓存重建）
//        //TODO 4.1 获取互斥锁
//        String lockey=RedisConstants.LOCK_SHOP_KEY+id;
//        Shop shop = null;
//        try {
//            boolean islock = tryLock(lockey);
//            //TODO 4.2 判断锁获取成功则开始重建
//            if (!islock) {
//                Thread.sleep(50);  //获取失败线程休眠
//                return queryWithMutex(id); //递归
//            }
//            shop = getById(id);
//            Thread.sleep(200); //模拟重建数据库的延迟
//            if (shop == null) {
//                //将空值写入Redis
//                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
//                //返回错误信息
//                return Result.fail("店铺不存在！");
//            }
//            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//            //TODO 4.3 判断锁获取失败则线程休眠并重试
//
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }finally {
//            //TODO 5 释放互斥锁
//            unlock(lockey);
//        }
//        //TODO 7 数据返回给客户
//        return Result.ok(shop);
//    }
//    private static final ExecutorService CHACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);//创建线程池，里面10个线程
//    public Result queryWithLogicalExpire(Long id){  //解决缓存穿透问题的代码-用逻辑缓存过期解决
//        //TODO 1 从redis中查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
//        //TODO 2 查询数据是否存在
//        if (StrUtil.isBlank(shopJson)) {
//            //TODO 2.1 不存在则直接向客户端返回数据
//            return Result.fail("该店铺不存在！");
//        }
//        //TODO 2.2 存在判断数据逻辑上是否过期
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        JSONObject data = (JSONObject) redisData.getData();  //上一句代码只是将redisdata做了反序列化，而redisdata里面还有一个Shop对象没有反序列化，下面同样要序列化
//        Shop shop = JSONUtil.toBean(data, Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        if (expireTime.isAfter(LocalDateTime.now())) {
//            //未过期直接返回数据
//            return Result.ok(shop);
//        }
//        //TODO 4 数据逻辑过期了需要缓存重建
//        //TODO 4.1 获取互斥锁
//        String lockkey=RedisConstants.LOCK_SHOP_KEY+id;
//        if (tryLock(lockkey)) {  //获取成功，开启独立线程开始缓存重建
//            CHACHE_REBUILD_EXECUTOR.submit(()->{
//                try {
//                    this.saveShop2Redis(id,20);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                }
//                finally {
//                    unlock(lockkey);  //释放锁
//                }
//
//            });//lambda表达式
//        }
//        //TODO 7 数据返回给客户
//        return Result.ok(shop);
//    }
//    public Result queryWithPassThrough(Long id){  //解决缓存穿透问题的代码-用互斥锁解决
//        //TODO 1 从redis中查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
//        //TODO 2 查询数据是否存在
//        if (StrUtil.isNotBlank(shopJson)) {
//            //TODO 3 存在则直接向客户端返回数据
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);//将Json字符串转换为实体类对象
//            return Result.ok(shop);
//        }
//        //isNotBlank将""作为false，我们已经将reids缓存中加入了空对象，如果还查数据库就出现来缓存穿透的问题，所以为了解决缓存穿透问题，我们需要再次判断空
//        if (shopJson!=null)
//        {
//            //返回一个错误信息
//            return  Result.fail("店铺信息不存在！");
//        }
//        Shop shop = getById(id);
//        //TODO 4 不存在则查询数据库
//        if (shop == null) {
//            //TODO 5.1 将空值写入Redis
//            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
//            //TODO 5.2 返回错误信息
//            return Result.fail("店铺不存在！");
//        }
//        //TODO 6 数据库中存在先写入Redis
//        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        //TODO 7 数据返回给客户
//        return Result.ok(shop);
//    }

    @Override
    public Result update(Shop shop) {
        if(shop.getId()==null)
            return  Result.fail("店铺ID不能为空！");
        //TODO 1 更新数据库
        updateById(shop);
        //TODO 2 删除缓存

        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1. 判断是否需要根据坐标查询
        if (x==null||y==null) {
            //1.2 不需要普通查询
            Page<Shop> page = query()
                    .eq("type_id",typeId)
                    .page(new Page<>(current,SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        // 需要按坐标查询
        //2. 计算分页参数
        int from=(current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end=current*SystemConstants.DEFAULT_PAGE_SIZE;
        //3. 查询Reidis、按照距离排序、分页
        String key=RedisConstants.SHOP_GEO_KEY+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),//5000m
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        //4. 解析出id
        if(results == null)
            return Result.ok();
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if(content.size()<=from)
        {
            //没有下一页
            return Result.ok();
        }
        //截取从from到end，因为limit(end)，没有from，是从0开始计算的
        List<Long> ids=new ArrayList<>(content.size());
        Map<String,Distance> distanceMap=new HashMap<>(content.size());
        content.stream().skip(from).forEach(result->{
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            Distance distance = result.getDistance();
            distanceMap.put(shopId,distance);
        });
        //5. 根据id查询数据库
        String join = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + join + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //6. 返回
        return Result.ok(shops);
    }
//    private boolean tryLock(String key) //模拟锁
//    {
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);//setIfAbsent是redis汇总的setnx命令
////setnx只有当key不存在时才能set，这里模拟锁的原理就是，如果一个人setnx调用后就设置了值，其它再Setnx就无法setnx了，这样就达到了一个互斥的效果
//        //释放锁时只需把setnx设置的数据删除即可
//        return BooleanUtil.isTrue(flag);
//    }
//    private  void unlock(String key){
//        stringRedisTemplate.delete(key);
//    }
//    public void saveShop2Redis(Long id,int expireSecond) throws InterruptedException //这个函数用来添加热点Key
//    {
//        //查询店铺信息
//        Shop shop=getById(id);
//        Thread.sleep(200); //模拟缓存重建的延迟
//        //封装逻辑过期时间
//        RedisData redisData=new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSecond));
//        //写入redis
//        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
//    }
}
