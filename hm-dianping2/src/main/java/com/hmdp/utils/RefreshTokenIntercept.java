package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenIntercept implements HandlerInterceptor {
    private StringRedisTemplate redisTemplate;
    public RefreshTokenIntercept(StringRedisTemplate redisTemplate)
    {
        this.redisTemplate=redisTemplate;
    }
    //这里不能使用@Autowire去注入，因为LoginInterceptor并不是我们容器中的对象，所以Spring不能做到依赖注入
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //TODO 1 获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;//直接放行
        }
        //TODO 2 基于token去获取redis中的用户信息
        Map<Object, Object> userMap = redisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
        if (userMap.isEmpty()) {
            return true;
        }
        //TODO 3 将查询到的信息从map转换为user对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //TODO 4 保存用户信息到ThreadLocal中
        UserHolder.saveUser(userDTO);
        //TODO 5 刷新token有效期
        redisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return true;
    }
}
