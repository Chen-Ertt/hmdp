package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

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
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    public Result queryById(Long id) {
        Shop shop;
        // 基于逻辑过期
        shop = cacheClient.queryByLogical("hmdp:shop:cache:", id, Shop.class,
                this::getById, "lock:shop");
        if(shop == null) {
            // 基于互斥锁
            shop = cacheClient.queryByLock("hmdp:shop:", id, Shop.class,
                    this::getById, "lock:shop");

            if(shop == null) {
                return Result.fail("店铺不存在");
            }
        }

        return Result.ok(shop);
    }


    /**
     * 先更新DB，后删除Cache，不需要更新cache
     */
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
