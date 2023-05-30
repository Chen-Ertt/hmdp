package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
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

    /**
     * 使用Redis分布式锁处理一人一单、解决炒卖问题
     * 并解决锁误删问题：在unlock前判断value
     * 1. 基于setnx中value为线程ID，但在分布式情况下，不同JVM可能会出现重复，不可行
     * 2. 使用UUID作为value
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2. 秒杀时间
        LocalDateTime beginTime = voucher.getBeginTime();
        LocalDateTime endTime = voucher.getEndTime();
        LocalDateTime now = LocalDateTime.now();
        if(now.isAfter(endTime)) {
            return Result.fail("秒杀已结束~");
        }
        if(now.isBefore(beginTime)) {
            return Result.fail("秒杀未开始~");
        }

        // 3. 库存是否充足
        Integer stock = voucher.getStock();
        if(stock < 1) {
            return Result.fail("库存不足~");
        }

        // 对用户加锁
        Long userID = UserHolder.getUser().getId();
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userID, stringRedisTemplate);
        boolean isLock = lock.tryLock(1200);
        if(!isLock) {
            return Result.fail("不允许重复下单");
        }
        try {
            return voucherOrderService.createVoucherOrder(voucherId);
        } finally {
            // value为UUID，在unlock前进行判断（封装在unlock()中）
            lock.unlock();
        }
    }

    /**
     * synchronized保证线程安全，锁的粒度是用户
     * 1. 先判断一人一单
     * 2. 扣除库存
     * 3. 创建订单并返回订单id
     */
    @NotNull
    // 存在一个问题，就是 @Transactional 在此处不会生效
    // 因为该注解功能的实现依赖于AOP：
    // 首先需要导入aspect依赖，并开启spring事务管理功能
    // 调用此方法是通过this.method()调用的，而this并非proxy对象，因此aop不生效
    // 也就是说必须通过代理对象访问自己，并调用方法才ok
    // 可以通过在类中注入自己
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 一人一单
        Long userID = UserHolder.getUser().getId();

        Long count = query().eq("user_id", userID).eq("voucher_id", voucherId).count();
        if(count > 0) {
            return Result.fail("购买数量现限制");
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
            return Result.fail("库存不足~");
        }

        // 5. 创建订单并返回订单id
        VoucherOrder order = new VoucherOrder();
        Long orderID = redisIDWorker.nextID("order");
        order.setId(orderID);

        order.setVoucherId(voucherId);

        order.setUserId(userID);
        save(order);

        return Result.ok(orderID);

    }
}
