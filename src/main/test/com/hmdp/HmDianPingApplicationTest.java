package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
public class HmDianPingApplicationTest {
    @Resource
    ShopServiceImpl shopService;
    @Resource
    CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void saveLogical() {
        cacheClient.prepareHotByRedis(2, 30L, shopService::getById);
    }


    @Test
    public void loadShopData() {
        List<Shop> list = shopService.list();

        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for(Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            List<Shop> value = entry.getValue();

            String key = "shop:geo:" + typeId;
            for(Shop shop : value) {
                stringRedisTemplate.opsForGeo().add(key,
                        new Point(shop.getX(), shop.getY()), shop.getId().toString());
            }
        }

    }
}
