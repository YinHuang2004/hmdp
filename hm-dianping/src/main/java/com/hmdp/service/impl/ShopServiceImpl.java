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

    /**
     * 根据id查询商铺数据
     *
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1、从Redis中查询店铺数据，并判断缓存是否命中
        Result result = getShopFromCache(key);
        if (Objects.nonNull(result)) {
            // 缓存命中，直接返回
            return result;
        }
        try {
            // 2、缓存未命中，需要重建缓存，判断能否能够获取互斥锁
            String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                // 2.1 获取锁失败，已有线程在重建缓存，则休眠重试
                Thread.sleep(50);
                return queryById(id);
            }
            // 2.2 获取锁成功，判断缓存是否重建，防止堆积的线程全部请求数据库（所以说双检是很有必要的）
            result = getShopFromCache(key);
            if (Objects.nonNull(result)) {
                // 缓存命中，直接返回
                return result;
            }

            // 3、从数据库中查询店铺数据，并判断数据库是否存在店铺数据
            Shop shop = this.getById(id);
            if (Objects.isNull(shop)) {
                // 数据库中不存在，缓存空对象（解决缓存穿透），返回失败信息
                renewCacheExpire(key,RedisConstants.CACHE_NULL_TTL,RedisConstants.NULL_RANDOM_RANGE,"");
                return Result.fail("店铺不存在");
            }

            // 4、数据库中存在，重建缓存，响应数据
            renewCacheExpire(key,RedisConstants.CACHE_SHOP_TTL,RedisConstants.HOT_RANDOM_RANGE,JSONUtil.toJsonStr(shop));
            return Result.ok(shop);
        }catch (Exception e){
            throw new RuntimeException("发生异常");
        } finally {
            // 5、释放锁（释放锁一定要记得放在finally中，防止死锁）
            unLock(key);
        }
    }

    /**
     * 从缓存中获取店铺数据
     * @param key
     * @return
     */
    private Result getShopFromCache(String key) {
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 缓存数据有值，说明缓存命中了，直接返回店铺数据
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        // 判断缓存中查询的数据是否是空字符串(isNotBlank把 null 和 空字符串 给排除了)
        if (Objects.nonNull(shopJson)) {
            // 当前数据是空字符串，说明缓存也命中了（该数据是之前缓存的空对象），直接返回失败信息
            return Result.fail("店铺不存在");
        }
        // 缓存未命中（缓存数据既没有值，又不是空字符串）
        return null;
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
//    public Shop queryWithMutex(Long id){
//        String key = "shop:"+id;
//
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        if (StrUtil.isNotBlank(shopJson)){//判断是否为null，空字符串，对象
//            //redis中存在，直接返回
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//
//        //如果redis中是空字符串
//        if (shopJson != null){
//            //不为null，那就是空字符串
//            return null;
//        }
//
//        //为空
//        String lockKey = "lock:shop"+id;
//        Shop shop = null;
//        try {
//            boolean lock = tryLock(lockKey);
//            if (!lock){
//                Thread.sleep(50);
//                return queryWithMutex(id);
//            }
//            shop = getById(id);
//            //模拟重建的延时
//            Thread.sleep(200);
//            if (shop == null){
//                //解决穿透，缓存控制
//                stringRedisTemplate.opsForValue().set(key,"",10, TimeUnit.MICROSECONDS);
//                return null;
//            }
//            //存在，写入redis
//            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),30,TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            unLock(lockKey);
//        }
//        return shop;
//    }


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




























