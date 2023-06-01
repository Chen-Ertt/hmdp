package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
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
    private StringRedisTemplate stringRedisTemplate;


    // 注入自己，是@Transactional能够生效
    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    // ID生成器
    @Resource
    private RedisIDWorker redisIDWorker;

    @Resource
    RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    // 线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 在类初始化完成后执行
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     *  线程任务
     *  无限循环读取阻塞队列中的任务并调用handleVoucherOrder()来处理
     */
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    VoucherOrder task = orderTasks.take();
                    handleVoucherOrder(task);
                } catch(Exception e) {
                    log.error("处理订单异常", e);
                }

            }
        }
    }

    /**
     * 加锁（Redisson）并将voucherorder写入DB中
     */
    private void handleVoucherOrder(VoucherOrder task) {
        Long userID = task.getUserId();
        RLock lock = redissonClient.getLock("order:" + userID);
        // 注意参数
        boolean isLock = lock.tryLock();
        if(!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            // 在DB创建订单
            voucherOrderService.createVoucherOrder(task);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 再进行一次判断（是否多余？），使用save()将定案的呢保存到DB
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder task) {
        // 一人一单
        Long userID = task.getUserId();
        Long voucherId = task.getVoucherId();

        Long count = query().eq("user_id", userID).eq("voucher_id", voucherId).count();
        if(count > 0) {
            log.error("购买数量限制");
            return;
        }

        // 4. 扣除库存
        boolean flag = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                // 判断库存是否相等，该做法存在库存遗留问题
                // .eq("stock", stock)

                // 判断库存>0
                .gt("stock", 0)
                .update();
        if(!flag) {
            log.error("库存不足");
            return;
        }

        save(task);

    }

    /**
     * 使用Redisson分布式锁（可重入锁）处理一人一单、解决炒卖问题
     * 并解决锁误删问题：在unlock前判断value
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 执行lua脚本，判断用户是否具有下单资格(return 0)
        Long userID = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(), userID.toString());
        if(result != 0) {
            return Result.fail(result == 1 ? "库存不足" : "超过购买限制");
        }

        // 2.将订单信息保存到队列，异步执行下单
        Long orderID = redisIDWorker.nextID("order");
        VoucherOrder order = new VoucherOrder();
        order.setId(orderID);
        order.setVoucherId(voucherId);
        order.setUserId(userID);

        orderTasks.add(order);

        return Result.ok(orderID);
    }
}
