package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenIntercept;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig  implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    //由Resource注解获取StringRedisTemplate对象
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/**/user/code",
                        "/**/user/login",
                        "/**/blog/hot",
                        "/**/shop/**",
                        "/**/shop-type/**",
                        "/**/voucher/**",
                        "/**/upload/**",
                        "/**/voucher-order/**"
                ).order(9);
        registry.addInterceptor(new RefreshTokenIntercept(stringRedisTemplate))
                .addPathPatterns(
                        "/**"   //拦截一切请求
                ).order(0);
        //order是拦截器实现底层用来决定拦截器执行顺序的方法，order越低越优先，这里我们是希望RefreshTokenIntercept先执行的
    }
}
