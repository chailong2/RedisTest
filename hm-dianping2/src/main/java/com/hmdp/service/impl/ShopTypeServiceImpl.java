package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        //TODO 1 从redis中查询商铺缓存
        Set<String> shopSet = stringRedisTemplate.opsForValue().getOperations().keys(RedisConstants.CACHE_SHOP_TYPE+"*");
        //TODO 2 查询数据是否存在
        if (!shopSet.isEmpty()) {
            //TODO 3 存在则直接向客户端返回数据
            List<ShopType> shoptype=new ArrayList<>();
            for(String shopinfo: shopSet)
            {
                shoptype.add(JSONUtil.toBean(stringRedisTemplate.opsForValue().get(shopinfo),ShopType.class));
            }
            return Result.ok(shoptype);
        }
        List<ShopType> typeList = query().orderByAsc("sort").list();  //使用MybatisPlus查询所有的店铺类型信息
        //TODO 4 不存在则查询数据库
        if (typeList == null) {
            //TODO 5 数据库中不存在返回错误即可
            return Result.fail("店铺类型不存在！");
        }
        //TODO 6 数据库中存在先写入Redis
        int i=0;
        for(ShopType shop:typeList)
        {
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_TYPE+i,JSONUtil.toJsonStr(shop));
            i++;
        }
        //TODO 7 数据返回给客户
        return Result.ok(typeList);
    }
}
