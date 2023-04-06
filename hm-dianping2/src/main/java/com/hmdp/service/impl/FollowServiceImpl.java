package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    IUserService userService;

    @Override
    public Result follow(Long id, Boolean isFollow) {
        //获取登录信息
        Long id1 = UserHolder.getUser().getId();
        String Key= RedisConstants.FLLOWS_KEY+id1;
        // 1.判断业务到底是关注还是取关
        if (isFollow) {
            //关注，新增数据
            Follow follow=new Follow();
            follow.setUserId(id1);
            follow.setFollowUserId(id);
            boolean issuccess = save(follow);
            if (issuccess) {
                //成功把关注的用户的id放进Redis
                stringRedisTemplate.opsForSet().add(Key, id.toString());
            }
            }else {
                boolean issuccess = remove(new QueryWrapper<Follow>().eq("user_id", id1).eq("follow_user_id", id));
                if (issuccess) {
                    stringRedisTemplate.opsForSet().remove(Key, id.toString());
                }
            }
        return Result.ok();
    }

    @Override
    public Result isfollow(Long id) {
        Long id1 = UserHolder.getUser().getId();
        //查询是否关注
        Integer count = query().eq("user_id", id1).eq("follow_user_id", id).count();
        return Result.ok(count>0);
    }

    @Override
    public Result followCommons(Long followUserid) {
       // 获取当前用户
       Long userId=UserHolder.getUser().getId();
        //求交集
        String key1=RedisConstants.FLLOWS_KEY+userId;
        String key2=RedisConstants.FLLOWS_KEY+followUserid;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        //将id解析
        List<Long> collect = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        if(collect==null||collect.isEmpty())
        {
            return  Result.ok();
        }
        //查询用户
        List<UserDTO> collect1 = userService.listByIds(collect).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(collect1);
    }
}
