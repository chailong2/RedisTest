package com.hmdp.service.impl;

import ch.qos.logback.classic.spi.EventArgUtil;
import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    RedisIdWorker redisIdWorker;
    private IVoucherOrderService prox;
    

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {  //脚本初始化
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);       //当一个线程尝试从阻塞队列中获取元素时，若阻塞队列中没有元素，则该线程会被阻塞
    private static final ExecutorService SECKILL_ORDER_EXCUTOR =Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXCUTOR.submit(new VoucherOrderHandler());
    }
    public class VoucherOrderHandler implements Runnable{
        String queuename="stream.orders";

        @Override
        public void run() {
            while(true)
            {
                try {
                    //获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queuename, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功
                    if(list==null||list.isEmpty())
                    {
                        //如果获取失败说明没有消息，继续下一次循环
                        continue;
                    }
                    //解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //获取成功，可以下单
                    handlerVocherorder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queuename,"g1",record.getId());
                } catch (Exception e) {
                   handlepeninglist();
                }
                //创建订单


            }
        }

        private void handlepeninglist() {
            while(true) {
                try {
                    //获取Pending-list中的订单信息
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queuename, ReadOffset.from("0"))
                    );
                    //判断消息是否获取成功
                    if (read == null || read.isEmpty()) {
                        //如果获取失败，说明pending-list没有异常消息，结束循环
                        break;
                    }
                    //解析消息
                    MapRecord<String, Object, Object> record = read.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //获取成功，可以下单
                    handlerVocherorder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queuename,"g1",record.getId());
                }catch (Exception e)
                {
                    log.error("处理pending-list异常",e);
                }
            }
        }
    }
//    public class VoucherOrderHandler implements Runnable{
//
//        @Override
//        public void run() {
//            while(true)
//            {
//                //获取队列中的订单信息
//                try {
//                    VoucherOrder take = orderTasks.take();
//                    handlerVocherorder(take);
//                } catch (Exception e) {
//                    log.error("处理订单异常",e);
//                }
//                //创建订单
//
//
//            }
//        }
//    }
    private void handlerVocherorder(VoucherOrder take){
        //获取用户
        Long userId=take.getUserId();
        //获取锁对象
        //SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("order:" + userId);
        //尝试获取锁
        //boolean islock = simpleRedisLock.tryLock(1200);
        boolean lock1 = lock.tryLock();
        //判断获取锁是否成功
        if (!lock1) {
            //获取不成功
            log.error("不允许重复下单");
            return ;
        }
        //获取成功
        try {
            prox.createVoucher(take);
        }finally {
            lock.unlock();
        }
    }
    @Resource
    RedissonClient redissonClient;
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠券
//        SeckillVoucher voucher=seckillVoucherService.getById(voucherId);
//        //判断秒杀是否结束
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("活动尚未开始!");
//        }
//        //判断秒杀是否结束
//        if(voucher.getEndTime().isBefore(LocalDateTime.now()))
//        {
//            return Result.fail("活动已经结束！");
//        }
//        //判断库存是否充足
//        if (voucher.getStock()<1) {
//            return Result.fail("库存不足！");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //获取锁对象
//        //SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("order:" + userId);
//        //尝试获取锁
//        //boolean islock = simpleRedisLock.tryLock(1200);
//        boolean lock1 = lock.tryLock();
//        //判断获取锁是否成功
//        if (!lock1) {
//            //获取不成功
//            return Result.fail("一个人只运行下一单！");
//        }
//        //获取成功
//        try {
//            IVoucherOrderService prox = (IVoucherOrderService) AopContext.currentProxy();
//            return prox.createVoucher(voucherId);
//        }finally {
//            lock.unlock();
//        }
//    }
//@Override
//public Result seckillVoucher(Long voucherId) {
//
//    Long id = UserHolder.getUser().getId();
//    //1.执行lua脚本
//    Long result = stringRedisTemplate.execute(
//            SECKILL_SCRIPT,
//            Collections.emptyList(),
//            voucherId.toString(), id.toString()
//    );
//    //2. 判断结果0
//    int r = result.intValue();
//    if (r!=0) {
//        //2.1 不为0，代表没有购买资格
//        return Result.fail(r==1? "库存不足":"不能重复下单");
//    }
//    //2.2 为0，有购买资格，把下单信息保存到阻塞队列中
//    long orderid = redisIdWorker.nextId("order");
//    //TODO 保存阻塞队列
//
//     //首先对订单信息进行封装
//    VoucherOrder voucherOrder=new VoucherOrder();
//    voucherOrder.setId(orderid);
//    voucherOrder.setUserId(id);
//    voucherOrder.setVoucherId(voucherId);
//    //信息保存到阻塞队列中
//    orderTasks.add(voucherOrder);
//    prox = (IVoucherOrderService) AopContext.currentProxy();
//    //3 返回订单ID
//
//    return Result.ok(orderid);
//}
@Override
public Result seckillVoucher(Long voucherId) {

    long orderid = redisIdWorker.nextId("order");
    Long id = UserHolder.getUser().getId();
    //1.执行lua脚本
    Long result = stringRedisTemplate.execute(
            SECKILL_SCRIPT,
            Collections.emptyList(),
            voucherId.toString(), id.toString(),String.valueOf(orderid)
    );
    //2. 判断结果0
    int r = result.intValue();
    if (r!=0) {
        //2.1 不为0，代表没有购买资格
        return Result.fail(r==1? "库存不足":"不能重复下单");
    }
    prox = (IVoucherOrderService) AopContext.currentProxy();
    return Result.ok(orderid);
}
    @Transactional
    public void createVoucher(VoucherOrder voucherorder) {
        //一人一单
        Long userId = voucherorder.getUserId();
        //查询订单
       //因为如果直接使用UserId的string作为锁条件是不行的，tostring方法每次都会创造新的String对象，调用 intern 方法时，
            // 如果池已包含与该方法确定equals(Object)的此对象相等的String字符串，则返回池中的字符串。否则，此String对象将添加到池中，并返回对此String对象的引用。
        int count = query().eq("user_id", userId).eq("voucher_id", voucherorder.getVoucherId()).count();
        if (count > 0) {
                //用于已经购买过
                log.error("用户已经购买过一次！");
            }
            //扣除库存
            boolean myvourcher = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id", voucherorder.getVoucherId()).gt("stock", 0).update();
            if (!myvourcher) {
                log.error("库存不足！");
            }
            //判断是否存在
            //创建订单
            save(voucherorder);
    }
}
