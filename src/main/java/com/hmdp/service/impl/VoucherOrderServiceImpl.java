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

    // ID生成器
    @Resource
    private RedisIDWorker redisIDWorker;

    /**
     * 基于乐观锁实现防止超卖问题
     * 使用库存stock作为version code
     * 将上锁转移给mysql（行锁）
     *
     * @param voucherId
     * @return
     */
    @Override
    @Transactional
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

        Long userID = UserHolder.getUser().getId();
        order.setUserId(userID);
        save(order);

        return Result.ok(orderID);
    }
}
