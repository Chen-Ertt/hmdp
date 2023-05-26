package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;


public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;
    // 由于没有加@Component，所以不能用@Autowired
    // 需要使用构造器，在MvcConfig中配置
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 在这一拦截器中，将拦截所有request，主要目的是进行token的刷新
     * preHandle()的最终结果都是true
     * 在这一拦截器中会获取token，获取用户，并将其存入ThreadLocal
     * @param request
     * @param response
     * @param handler
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取request Header中的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)) {
            return true;
        }

        // 2. 基于token获取redis中的用户信息
        String tokenKey = "hmdp:token:" + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        if(userMap.isEmpty()) {
            return true;
        }
        // 查到的HashMap转为UserDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        // 3. 保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);

        // 4. 刷新token有效期
        stringRedisTemplate.expire(tokenKey, 30, TimeUnit.MINUTES);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
