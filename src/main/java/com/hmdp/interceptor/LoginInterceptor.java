package com.hmdp.interceptor;

import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    /**
     * 已经将用户相关的操作放到RefreshTokenInterceptor中
     * 这一部分只需要判断是否需要拦截，也就是ThreadLocal中是否存在User信息
     * @param request
     * @param response
     * @param handler
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if(UserHolder.getUser()==null){
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
