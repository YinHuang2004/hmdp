package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import io.netty.util.internal.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        //如果有缓存则直接返回，没有缓存则查询数据库并存储到缓存中
        String key= RedisConstants.CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            //说明缓存值为店铺数据
            //反序列化为java对象
            //延长缓存过期时间,覆盖
            renewCacheExpire(key,RedisConstants.CACHE_SHOP_TTL,RedisConstants.HOT_RANDOM_RANGE,shopJson);
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //执行到这里说明缓存值要么为null或者空缓存对象""
        if(Objects.nonNull(shopJson)){
            //说明缓存值为""
            return Result.fail("店铺不存在");
        }
        //说明数据库不存在缓存那么查询数据库，查询完后记得回填缓存(空对象或有效值）
        Shop shop = this.getById(id);
        //说明数据库不存在，缓存空对象
        if(shop==null){
            // 解决缓存穿透：将空值存入缓存，设置较短的过期时间（比如2分钟）
            renewCacheExpire(key,RedisConstants.CACHE_NULL_TTL,RedisConstants.NULL_RANDOM_RANGE,"");
            return Result.fail("店铺不存在");
        }
        //添加有效缓存值
        renewCacheExpire(key,RedisConstants.CACHE_SHOP_TTL,RedisConstants.HOT_RANDOM_RANGE,JSONUtil.toJsonStr(shop));
        return Result.ok(shop);
    }

    private void renewCacheExpire(String key,Long ttl,Long randomTtl,String json) {
        Long t=ttl+ ThreadLocalRandom.current().nextLong(randomTtl);
        stringRedisTemplate.opsForValue().set(key,json,t,TimeUnit.MINUTES);
    }


    //把删除缓存和修改数据库在一个事务中
    @Transactional
    @Override
    public void updateShop(Shop shop) {
        boolean isSuccess = updateById(shop);
        if(!isSuccess){
            throw new RuntimeException("数据库更新失败");
        }
        //删除缓存
        String key=RedisConstants.CACHE_SHOP_KEY+shop.getId();
        isSuccess = stringRedisTemplate.delete(key);
        if(!isSuccess){
            //缓存删除失败，抛出异常，事务回滚
            throw new RuntimeException("删除缓存失败");
        }
    }

    //解决缓存穿透和使用互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id){
        String key = "shop:"+id;

        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)){//判断是否为null，空字符串，对象
            //redis中存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //如果redis中是空字符串
        if (shopJson != null){
            //不为null，那就是空字符串
            return null;
        }

        //为空
        String lockKey = "lock:shop"+id;
        Shop shop = null;
        try {
            boolean lock = tryLock(lockKey);
            if (!lock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            shop = getById(id);
            //模拟重建的延时
            Thread.sleep(200);
            if (shop == null){
                //解决穿透，缓存控制
                stringRedisTemplate.opsForValue().set(key,"",10, TimeUnit.MICROSECONDS);
                return null;
            }
            //存在，写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),30,TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }
        return shop;
    }


    //获取锁

    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}




























