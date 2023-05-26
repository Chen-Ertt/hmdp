package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryByRedis() {
        String shopType = "hmdp:shop:type";
        // 1. 从redis查询缓存并判断缓存是否命中
        List<String> shopTypeStr = stringRedisTemplate.opsForList().range(shopType, 0, -1);
        if(shopTypeStr!=null && !shopTypeStr.isEmpty()) {
            List<ShopType> shopTypeList = new ArrayList<>();
            for(String str: shopTypeStr) {
                ShopType type = JSONUtil.toBean(str, ShopType.class);
                shopTypeList.add(type);
            }
            return Result.ok(shopTypeList);
        }

        // 2. 未命中，进行数据库query
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        if(shopTypeList == null) {
            return Result.fail("不存在");
        }

        // 3. 将数据写入redis
        for(ShopType type : shopTypeList) {
            stringRedisTemplate.opsForList().rightPush(shopType, JSONUtil.toJsonStr(type));
        }

        return Result.ok(shopTypeList);
    }
}
