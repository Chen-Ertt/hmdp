package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    StringRedisTemplate stringRedisTemplate;

    /**
     * 使用redis缓存并读取店铺信息
     * 设置过期时间30分钟
     * 需要处理缓存穿透问题，对于DB不存在的数据，将空值("")写入redis，ttl为5mins
     * 基于互斥锁解决了缓存击穿的问题
     */
    @Override
    public Result queryById(Long id) {
        String shopID = "hmdp:shop:" + id.toString();
        // 1. 从redis查询缓存并判断缓存是否命中
        String shopJson = stringRedisTemplate.opsForValue().get(shopID);
        if(StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        // 判断命中是否为空值
        if(shopJson != null) {
            return Result.fail("店铺不存在");
        }
        String lock = null;
        Shop shop = null;

        try {
            // 2. 未命中，尝试获取锁进行cache重建
            lock = "lock:shop" + id;
            // 未能获取到锁，进行休眠
            if(!tryLock(lock)) {
                Thread.sleep(500);
                // 递归查询
                return queryById(id);
            }

            // 若成功获取锁，根据id查询DB
            shop = getById(id);

            // 处理缓存穿透问题
            // 若shopID不存在，将其写入redis，对应空值，设置ttl
            if(shop == null) {
                stringRedisTemplate.opsForValue().set(shopID, "", 5, TimeUnit.MINUTES);
                return Result.fail("店铺不存在");
            }

            // 3. 将数据写入redis
            stringRedisTemplate.opsForValue().set(shopID, JSONUtil.toJsonStr(shop));
            stringRedisTemplate.expire(shopID, 30, TimeUnit.MINUTES);
        } catch(InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 4. 释放锁
            unlock(lock);
        }

        return Result.ok(shop);
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if(id == null) {
            return Result.fail("ID为空");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除缓存
        String shopID = "hmdp:shop:" + id;
        stringRedisTemplate.delete(shopID);
        // 不需要更新缓存，等到读操作再更新

        return Result.ok();
    }
}
