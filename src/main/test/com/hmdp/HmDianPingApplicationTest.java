package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
public class HmDianPingApplicationTest {
    @Resource
    ShopServiceImpl shopService;

    @Test
    public void saveLogical(){
        shopService.saveShop2Redis(5L, 1L);
    }
}
