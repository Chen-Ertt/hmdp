package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

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
    @Resource
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

    /**
     * 根据GEO获取附近商铺
     * 实现难点为分页查询
     * 主要思路为
     * 1. 先根据所传参数是否带有坐标（x，y）判断是否需要显示距离信息
     * 2. 查询redis，得到其中存储的店铺id和distance
     * 3. 使用stream来获取分页内容
     * 4. 根据所得到的店铺id获取店铺，加上distance信息并返回
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if(x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis，得到ship id和distance
        String key = "shop:geo:" + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if(results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if(content.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        ArrayList<Long> ids = new ArrayList<>(content.size());
        HashMap<String, Distance> distanceMap = new HashMap<>(content.size());

        content.stream().skip(from).forEach(result -> {
            // 获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 获取distance
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });

        // 4.根据id查询shop并返回
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for(Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
