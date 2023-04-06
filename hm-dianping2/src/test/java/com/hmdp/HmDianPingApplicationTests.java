package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import io.lettuce.core.api.reactive.RedisGeoReactiveCommands;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest
class HmDianPingApplicationTests {
  @Resource
  private ShopServiceImpl shopService;
  @Resource
  private RedisIdWorker redisIdWorker;
  @Resource
  private RedissonClient redissonClient;
  @Resource
  private StringRedisTemplate stringRedisTemplate;
  private RLock rLock;
  @BeforeEach
  void setUp(){
    rLock=redissonClient.getLock("order");
  }
  private ExecutorService executorService = Executors.newFixedThreadPool(500);//定义线程池


  @Test
  void testSaveShop() throws InterruptedException {
    CountDownLatch latch=new CountDownLatch(300);
    Runnable task = () -> {
      for (int i = 0; i < 100; i++) {
        long id = redisIdWorker.nextId("order");
        System.out.println("id=" + id);
      }
      latch.countDown();
    };
    long begin=System.currentTimeMillis();
    for (int i = 0; i < 300; i++) {
      executorService.submit(task);
    }
    latch.await();
    long end=System.currentTimeMillis();
    System.out.println("time+"+(end-begin));
  }
  @Test
  void method1() throws InterruptedException {
    boolean isLock=rLock.tryLock(1L, TimeUnit.SECONDS);
    if (!isLock) {
      log.error("获取锁失败.....1");
      return;
    }
    try{
      log.info("获取锁成功....1");
      method2();
      log.info("开始执行业务....1");
    }finally {
      log.warn("准备释放锁....1");
      rLock.unlock();
    }
  }

  private void method2() throws InterruptedException {
    boolean isLock=rLock.tryLock(1L, TimeUnit.SECONDS);
    if (!isLock) {
      log.error("获取锁失败.....2");
      return;
    }
    try{
      log.info("获取锁成功....2");
      log.info("开始执行业务....2");
    }finally {
      log.warn("准备释放锁....2");
      rLock.unlock();
    }
  }

  @Test
  void loadshop(){
    //1. 查询店铺信息
    List<Shop> list=shopService.list();
    //2. 把店铺分组（按照TypeId分组）
    Map<Long,List<Shop>> map=list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
    //3. 分批写入redis
    for (Map.Entry<Long, List<Shop>> longListEntry : map.entrySet()) {
        //获取类型id
      Long key = longListEntry.getKey();
      //获取同类型的店铺的集合
      List<Shop> value=longListEntry.getValue();
      String mykey= RedisConstants.SHOP_GEO_KEY+key;
        //写入redis
      List<RedisGeoCommands.GeoLocation<String>> locations=new ArrayList<>(value.size());
      for (Shop shop : value) {
        //stringRedisTemplate.opsForGeo().add(mykey, new Point(shop.getX(),shop.getY()),shop.getId().toString());
        locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
      }
      stringRedisTemplate.opsForGeo().add(mykey,locations);//批量写
    }
  }
  @Test
  void testHyperLogLog(){
    //准备数组，装用户数据
    String[] users=new String[1000];
    //数组角标
    int index=0;
    for (int i=1;i<1000000;i++){
      //赋值
      users[index++]="user_"+i;
      //每1000条发送一次
      if(i%1000==0){
        index=0;
        stringRedisTemplate.opsForHyperLogLog().add("hll1",users);
      }
    }
    //统计数量
    Long size=stringRedisTemplate.opsForHyperLogLog().size("hll1");
    System.out.println("size="+size);
  }
}