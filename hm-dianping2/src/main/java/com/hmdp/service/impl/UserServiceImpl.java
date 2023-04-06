package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.mapper.UserInfoMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate redisTemplate;  //注入SpringBoot自带的Redis使用模块
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone))
        {
            //2.如果不符合返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //3.符合生产验证码
        String code=RandomUtil.randomNumbers(6); //随机生产六位数字
        //4.保存验证码到redis
        redisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL,TimeUnit.MINUTES);//设置这条数据的有效期为2分钟，两分钟后会自动删除
        //为了防止验证码信息一直保存到redis中，影响redis的性能，所以要给每个验证码信息设置一个有效期
        //5.发送验证码
        log.debug("发送短信验证码成功"+code);//这里模拟发送，真正要发送要调用Ali的相关的服务

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone()))
        {
            //2.如果不符合返回错误信息
            return Result.fail("手机号格式错误！");
        }
        String cachecode =redisTemplate.opsForValue().get(LOGIN_CODE_KEY+loginForm.getPhone());
        String code=loginForm.getCode();
        if(code==null ||!cachecode.equals(code))
        {
            return Result.fail("验证码不一致！");
        }
        User user=query().eq("phone",loginForm.getPhone()).one();
        //判断用户是否存在
        if (user == null) {
            //不存在保存
            user=createUserwithPhone(loginForm.getPhone());
        }
        //保存用户信息
        //生成一个token作为登录名牌
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO=BeanUtil.copyProperties(user, UserDTO.class);
        //将user转化为hash对象存储到redis中
        Map<String, Object> usermap = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true).
                setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));//将userBTO转换为Map对象，这里用到了lambda表达式
        String tokenKey=LOGIN_USER_KEY+token;
        System.out.println(tokenKey);
        redisTemplate.opsForHash().putAll(tokenKey,usermap);
        redisTemplate.expire(LOGIN_USER_KEY,LOGIN_USER_TTL,TimeUnit.MINUTES);//这里表示用户登录后30分钟Token会自动过期，但是
        //这与我们的实际session的业务逻辑不同，session是只要用户访问一次，有效期就会恢复到30分钟，所以我们也要修改redis的有效期为，只要用户
        //访问了，有效期就会增加到30分钟，如何知道用户登录了，拦截器可以做到（拦截器会对用户所有的请求进行处理）
        //保存数据到redis中
        //将token返回给客户端
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //获取当前登录的用户
        Long id = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now=LocalDateTime.now();
        //拼接key
        String keysuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key= USER_SIGN_KEY+id+keysuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth()-1;
        //写入redis
        redisTemplate.opsForValue().setBit(key,dayOfMonth,true);
        return Result.ok();
    }

    @Override
    public Result signCounet() {
        //获取当前登录的用户
        Long id = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now=LocalDateTime.now();
        //拼接key
        String keysuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key= USER_SIGN_KEY+id+keysuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth()-1;
        //获取本月截止到今天为止所有的签到记录，返回的是一个十进制数
        List<Long> result = redisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth + 1)).valueAt(0));
        //循环遍历
        if(result==null||result.isEmpty())
        {
            return  Result.ok();
        }
        Long num=result.get(0);
        if(num==null||num==0)
            return Result.ok();
        int count=0;
        while(true)
        {
            if ((num & 1)==0) {
                break;
            }else{
                count ++;
            }
            num>>>=1;
        }
        return Result.ok(count);
    }

    private User createUserwithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
