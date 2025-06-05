package com.hmdp.service.impl;

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
import org.springframework.web.bind.annotation.PostMapping;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    // 加载脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private IVoucherOrderService proxy;

    // 创建单线程线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

//    @PostConstruct //在当前类初始化完毕后执行
//    private void init(){
//        // 确保 stream.orders 存在，否则创建 group 会报错
//        try {
//            stringRedisTemplate.opsForStream().createGroup("stream.orders", ReadOffset.from("0"), "g1");
//        } catch (Exception e) {
//            if (!e.getMessage().contains("BUSYGROUP")) {
//                log.error("创建消费者组失败", e);
//            }
//        }
//        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
//    }
//    private class VoucherOrderHandler implements Runnable{
//        String queueName = "stream.orders";
//        @Override
//        public void run() {
//            while(true){
//                try {
//                    // 1.获取消息队列中的订单信息
//                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
//                            Consumer.from("g1", "c1"),
//                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
//                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
//                    );
//                    // 2.判断消息获取是否成功
//                    // 2.1.如果消息获取失败，继续下一次循环
//                    if(list==null || list.isEmpty()){
//                        continue;
//                    }
//                    // 3.解析消息中的订单信息
//                    MapRecord<String, Object, Object> record = list.get(0);
//                    Map<Object, Object> value = record.getValue();
//                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
//                    // 3.如果有消息，可以下单
//                    handleVoucherOrder(voucherOrder);
//                    // ACK确认
//                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1",record.getId());
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                    handlePendingList();
//                }
//            }
//        }
//
//        private void handlePendingList() {
//            while(true){
//                try {
//                    // 1.获取pendinglist队列中的订单信息
//                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
//                            Consumer.from("g1", "c1"),
//                            StreamReadOptions.empty().count(1),
//                            StreamOffset.create(queueName, ReadOffset.from("0"))
//                    );
//                    // 2.判断消息获取是否成功
//                    // 2.1.如果消息获取失败，说明pending-list没有异常消息，结束循环
//                    if(list==null || list.isEmpty()){
//                        break;
//                    }
//                    // 3.解析消息中的订单信息
//                    MapRecord<String, Object, Object> record = list.get(0);
//                    Map<Object, Object> value = record.getValue();
//                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
//                    // 3.如果有消息，可以下单
//                    handleVoucherOrder(voucherOrder);
//                    // ACK确认
//                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1",record.getId());
//                } catch (Exception e) {
//                    log.error("处理pending-list订单异常", e);
//                    try {
//                        Thread.sleep(20);
//                    } catch (InterruptedException interruptedException) {
//                        interruptedException.printStackTrace();
//                    }
//                }
//            }
//        }
//    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

    @PostConstruct //在当前类初始化完毕后执行
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3.获取锁
        boolean isLock = lock.tryLock();
        if(!isLock){
            log.error("不允许重复下单！");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }


//    @Override
//    public Result seckillVoucher(Long voucherId){
//
//        // 1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2.判断秒杀时间
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 尚未开始
//            return Result.fail("秒杀尚未开始！");
//        }
//        if(voucher.getEndTime().isEqual(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束！");
//        }
//
//        // 3.判断库存
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足！");
//        }
//
//        Long id = UserHolder.getUser().getId();
////        synchronized (id.toString().intern()) {
////            // 获取代理对象（事务）事务提交后才释放锁，避免没提交就释放锁导致的一人一单失效问题
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//
//        // 创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + id, stringRedisTemplate);
//        // 获取锁
//        RLock lock = redissonClient.getLock("lock:order:" + id);
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            return Result.fail("不允许重复下单！");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//
//    }

    @Override
    public Result seckillVoucher(Long voucherId){
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        // 2.判断结果是0
        int r = result.intValue();
        if(r != 0){
            // 2.1.不为0，没有购买资格
            return Result.fail(r == 1?"库存不足":"不能重复下单");
        }

        // 3.获取主线程的代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 2.2.为0，将下单信息保存到阻塞队列中
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.3.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 2.4.用户id
        voucherOrder.setUserId(userId);
        // 2.5.代金券id
        voucherOrder.setVoucherId(voucherId);
        // 2.6.放入阻塞队列
        orderTasks.add(voucherOrder);

        // 3.返回订单id
        return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId){
//        Long userId = UserHolder.getUser().getId();
//        // 2.3.订单id
//        long orderId = redisIdWorker.nextId("order");
//        // 1.执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString(), String.valueOf(orderId)
//        );
//        // 2.判断结果是0
//        int r = result.intValue();
//        if(r != 0){
//            // 2.1.不为0，没有购买资格
//            return Result.fail(r == 1?"库存不足":"不能重复下单");
//        }
//
//        // 3.获取主线程的代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//        // 3.返回订单id
//        return Result.ok(orderId);
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5.一人一单
        Long id = voucherOrder.getUserId();
        // 5.1.查询订单
        int count = query().eq("user_id", id).eq("voucher_id", voucherOrder.getVoucherId()).count();

        // 5.2.判断是否存在
        if (count > 0) {
            log.error("用户已经购买过一次！");
            return;
        }

        // 4.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足！");
            return;
        }

        save(voucherOrder);
//        log.info("成功添加订单");
    }


//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        // 5.一人一单
//        Long id = UserHolder.getUser().getId();
//        // 5.1.查询订单
//        int count = query().eq("user_id", id).eq("voucher_id", voucherId).count();
//
//        // 5.2.判断是否存在
//        if (count > 0) {
//            return Result.fail("用户已经购买过一次！");
//        }
//
//        // 4.扣减库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock -1")
//                .eq("voucher_id", voucherId)
//                .gt("stock", 0)
//                .update();
//        if (!success) {
//            return Result.fail("库存不足！");
//        }
//
//        // 5.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 5.1.订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        // 5.2.用户id
//        voucherOrder.setUserId(id);
//        // 5.3. 代金券id
//        voucherOrder.setVoucherId(voucherId);
//        save(voucherOrder);
//        // 6.返回订单id
//        return Result.ok(orderId);
//    }
}
