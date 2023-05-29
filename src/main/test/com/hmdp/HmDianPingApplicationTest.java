package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
public class HmDianPingApplicationTest {
    @Resource
    ShopServiceImpl shopService;
    @Resource
    CacheClient cacheClient;

    @Test
    public void saveLogical(){
        cacheClient.prepareHotByRedis(2, 30L, shopService::getById);
    }
}
