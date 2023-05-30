package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.UserHolder;
import org.jetbrains.annotations.NotNull;
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

    // 注入自己，是@Transactional能够生效
    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    // ID生成器
    @Resource
    private RedisIDWorker redisIDWorker;

    /**
     * 基于乐观锁实现防止超卖问题
     * 使用库存stock作为version code
     * 将上锁转移给mysql（行锁）
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

        // 对用户加锁：
        // 所有用户用同一把锁，效率过低
        // 对方法加锁：
        // 考虑到先释放锁，后提交事务，会导致数据未被commit前，其他线程获取到锁，因此需要对整个方法加锁
        Long userID = UserHolder.getUser().getId();
        synchronized(userID.toString().intern()) {
            return voucherOrderService.createVoucherOrder(voucherId);
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
