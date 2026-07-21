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
     * 根据id查询商铺信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //首先加载缓存
        String key=RedisConstants.CACHE_SHOP_KEY+id;
        Result shopFromCache = getShopFromCache(key);
        //判断缓存是否存在
       if(Objects.nonNull(shopFromCache)){
           //缓存命中
           return shopFromCache;
       }
       //缓存未命中需要重建缓存
        try{
            //判断是否能获取到互斥锁
            boolean isSuccessToMutexLock=tryLock(key);
            if(!isSuccessToMutexLock){
                //获取互斥锁失败，说明已经有其他线程重建缓存，当前线程睡眠
                Thread.sleep(50);
                return queryById(id);
            }
            //获取锁成功,判断缓存是否重建(双检)，防止堆积的线程全部请求数据库
            shopFromCache=getShopFromCache(key);
            if(Objects.nonNull(shopFromCache)){
                //缓存命中
                return shopFromCache;
            }
            //获取锁成功说明当前线程需要查询数据库回填
            Shop shop=getById(id);
            if(Objects.isNull(shop)){
                //数据库不存在，返回空缓存，防止穿透
                renewCacheExpire(key,RedisConstants.CACHE_NULL_TTL,RedisConstants.NULL_RANDOM_RANGE,"");
                return Result.fail("店铺不存在");
            }
            //数据库存在则需要回填
            renewCacheExpire(key,RedisConstants.CACHE_SHOP_TTL,RedisConstants.HOT_RANDOM_RANGE,JSONUtil.toJsonStr(shop));
            return Result.ok(shop);
        }catch (Exception e){
            throw new RuntimeException("发生异常");
        }finally {
            unLock(key);
        }
    }
    //从redis加载缓存
    private Result getShopFromCache(String key){
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断缓存是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //缓存命中
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //缓存值无效，通过判断是否是非空判断是否是空缓存
        if(Objects.nonNull(shopJson)){
            //说明是空缓存
            return Result.fail("店铺不存在");
        }
        //说明数据库不存在这样的对象，那么需要重建缓存，（缓存可能刚刚失效）
        return null;
    }
    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    public void unLock(String key){
        stringRedisTemplate.delete(key);
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



}




























