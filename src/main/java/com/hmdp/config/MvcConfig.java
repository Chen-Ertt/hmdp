package com.hmdp.config;

import com.hmdp.interceptor.LoginInterceptor;
import com.hmdp.interceptor.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        /**
         * 注意拦截器的顺序
         * 默认都为0，以添加顺序为标注
         * 也可以设置order，越小越优先
         * 如：registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(1);
         */
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate));

        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code", "/user/login", "/blog/hot",
                        "/shop/**", "shop-type/**", "voucher/**", "/upload/**");
    }
}
