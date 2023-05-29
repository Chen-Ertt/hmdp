package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.RedisDataDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    public Result queryById(Long id) {
        Shop shop;

        // 基于逻辑过期
        shop = queryByLogical(id);
        if(shop == null) {
            // 基于互斥锁
            shop = queryByLock(id);

            if(shop == null) {
                return Result.fail("店铺不存在");
            }
        }

        return Result.ok(shop);
    }

    /**
     * 使用redis缓存并读取店铺信息
     * 设置过期时间30分钟
     * 需要处理缓存穿透问题，对于DB不存在的数据，将空值("")写入redis，ttl为5mins
     * 基于互斥锁解决了缓存击穿的问题
     */

    public Shop queryByLock(Long id) {
        String shopID = "hmdp:shop:" + id.toString();
        // 1. 从redis查询缓存并判断缓存是否命中
        String shopJson = stringRedisTemplate.opsForValue().get(shopID);
        if(StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中是否为空值
        if(shopJson != null) {
            return null;
        }
        String lock = null;
        Shop shop;

        try {
            // 2. 未命中，尝试获取锁进行cache重建
            lock = "lock:shop" + id;
            // 未能获取到锁，进行休眠
            if(!tryLock(lock)) {
                Thread.sleep(500);
                // 递归查询
                return queryByLock(id);
            }

            // 若成功获取锁，根据id查询DB
            shop = getById(id);

            // 处理缓存穿透问题
            // 若shopID不存在，将其写入redis，对应空值，设置ttl
            if(shop == null) {
                stringRedisTemplate.opsForValue().set(shopID, "", 5, TimeUnit.MINUTES);
                return null;
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

        return shop;
    }

    // 线程池
    private static final ExecutorService cache_rebuild = Executors.newCachedThreadPool();


    private Shop queryByLogical(Long id) {
        String shopID = "hmdp:shop:cache:" + id.toString();
        // 1. 从redis查询缓存并判断缓存是否命中
        String shopJson = stringRedisTemplate.opsForValue().get(shopID);
        // 未命中，直接返回null
        if(StrUtil.isBlank(shopJson)) {
            return null;
        }

        // 2. 获取逻辑时间，判断是否过期
        RedisDataDTO redisData = JSONUtil.toBean(shopJson, RedisDataDTO.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime exprireTime = redisData.getExprireTime();
        // 未过期，直接返回
        if(exprireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        // 已过期
        String lock = "lock:shop:cache" + id;
        boolean flag = tryLock(lock);

        // 3.若获取到lock，进行缓存重建
        if(flag) {
            // 开启线程进行重建
            try {
                cache_rebuild.submit(() -> saveShop2Redis(id, 1L));
            } catch(Exception e) {
                throw new RuntimeException(e);
            } finally {
                unlock(shopID);
            }
        }

        return shop;
    }


    /**
     * 指定shop和逻辑过期时间
     * 向redis存入该shop
     */
    public void saveShop2Redis(Long id, Long minutes) {
        Shop shop = getById(id);
        RedisDataDTO<Shop> redisShop = new RedisDataDTO<>(LocalDateTime.now().plusMinutes(minutes), shop);
        String shopID = "hmdp:shop:cache:" + id.toString();
        stringRedisTemplate.opsForValue().set(shopID, JSONUtil.toJsonStr(redisShop));
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
        String shopID = "hmdp:shop" + id;
        stringRedisTemplate.delete(shopID);
        // 不需要更新缓存，等到读操作再更新

        return Result.ok();
    }
}
