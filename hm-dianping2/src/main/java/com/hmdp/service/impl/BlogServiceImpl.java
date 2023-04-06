package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;
    @Override
    public Result queryBlogByid(Long id) {
        //查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在！");
        }
        queryBlogUser(blog);
        //查询blog是否被点赞了
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //获取登录信息
        UserDTO user = UserHolder.getUser();
        if(user==null){
            return;
        }
        Long userId=user.getId();
        //判断当前用户是否已经点赞过了
        String key= RedisConstants.BLOG_LIKED_KEY+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(BooleanUtil.isTrue(score !=null));  //这标示已经点过赞了
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page =query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog->{
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        //获取登录信息
        UserDTO user = UserHolder.getUser();
        Long userId=user.getId();
        //判断当前用户是否已经点赞过了
        String key= RedisConstants.BLOG_LIKED_KEY+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score==null) {
            //如果未点赞
            //1. 数据库点赞数+1
            boolean idsuccess = update().setSql("liked=liked+1").eq("id", id).update();
            if (idsuccess) {
                //2. 保存用户到Redis集合
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else{
            //如果已经点赞，取消点赞
            //1. 数据库点赞数-1
            boolean idsuccess = update().setSql("liked=liked-1").eq("id", id).update();
            //2. 把用户从redis集合中移除
            if (idsuccess) {
                //2. 保存用户到Redis集合
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }

        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        // 构造 Redis 中存储点赞用户的键名
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 从 Redis 中查询点赞用户中的前五个，使用的是有序集合
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5==null||top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 将点赞用户的 ID 转化为 Long 类型，并保存到 List 中
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idstr = StrUtil.join(",", ids);
        // 根据点赞用户的 ID，查询对应的用户信息，并将其转化为 UserDTO 类型的数据流
        Stream<UserDTO> userDTOStream = userService.query().in("id",ids).last("ORDER BY FIELD(id,"+idstr+")").list().stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class));
        //返回
        return Result.ok(userDTOStream);
    }

    @Override
    public Result saveBlog(Blog blog) {
        //获取登录用户
        UserDTO user=UserHolder.getUser();
        blog.setUserId(user.getId());
        //保存探店笔记
        boolean isSucess=save(blog);
        if (!isSucess) {
            return Result.fail("新增笔记失败！");
        }
        //查询笔记作者的所有粉丝 select * from tb_follow where follow_ser_id=?
        List<Follow> followUserId = followService.query().eq("follow_user_id", user.getId()).list();
        //推送所有笔记给粉丝（推模式）
        for(Follow follow:followUserId)
        {
            //获取粉丝ID
            Long userid = follow.getUserId();
            System.out.println("jakie"+userid);
            //推送
            String key=RedisConstants.FEED_KEY+userid;
            Boolean add = stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(),System.currentTimeMillis());

        }
        //返回ID
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1. 找到收件箱
        //1.1 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //1.2 拿到当前用户的收件箱
        String key=RedisConstants.FEED_KEY+userId;
        //TODO 滚动分页查询
        Set<ZSetOperations.TypedTuple<String>> mailSet = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (mailSet==null|| mailSet.isEmpty()) {
            return Result.ok();
        }
        long minitime=0;
        int myoffset=1;
        List<Long> ids=new ArrayList<>(mailSet.size());
        //2. 解析数据：blogId，时间戳score，offset
        for (ZSetOperations.TypedTuple<String> tuple : mailSet) {
            //获取id
            String idstr = tuple.getValue();
            ids.add(Long.valueOf(idstr));
            //获取分数(时间戳）
            long time = tuple.getScore().longValue();
            if(minitime==time)
                myoffset++;
            else {
                minitime=time;
                myoffset = 1;
            }
        }
        String str = StrUtil.join(",", ids);
        System.out.println("test");
        //3. 根据ID查询Blog
        List<Blog> blogs=query().in("id",ids).last("ORDER BY FIELD(id,"+str+")").list();
        for (Blog blog : blogs) {
            //查询Blog的相关客户
            queryBlogUser(blog);
            //查询Blog是否被点赞
            isBlogLiked(blog);
        }
        //4. 封装并返回
        ScrollResult r=new ScrollResult();
        r.setList(blogs);
        r.setOffset(myoffset);
        r.setMinTime(minitime);
        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
