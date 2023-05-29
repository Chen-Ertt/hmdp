package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.RedisDataDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将对象存储到redis中，并设置过期时间ttl
     *
     * @param key    Redis对应key
     * @param object 对象
     * @param time   时间数值
     * @param unit   时间单位
     */
    public void set2Redis(String key, Object object, Long time, TimeUnit unit) {
        String value = JSONUtil.toJsonStr(object);
        stringRedisTemplate.opsForValue().set(key, value, time, unit);
    }

    /**
     * 将对象存储到redis中，永不过期
     * 但有逻辑过期时间，因此使用RedisDataDTO类进行存储
     *
     * @param key    redis key
     * @param object 对象
     * @param time   逻辑过期时间数值
     * @param unit   时间单位
     * @param <T>    Object的类型
     */
    public <T> void set2RedisWithLogical(String key, T object, Long time, TimeUnit unit) {
        // 传入对象为RedisData
        RedisDataDTO<T> redisData = new RedisDataDTO<>(LocalDateTime.now().plusMinutes(unit.toMinutes(time)),
                object);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 使用redis缓存并读取店铺信息
     * 设置过期时间30分钟
     * 缓存穿透：
     * 对于DB不存在的数据，将空值("")写入redis，ttl为5mins
     * 缓存击穿：
     * 互斥锁
     */
    public <R, ID> R queryByLock(String prefix, ID id, Class<R> type, Function<ID, R> dbFallback, String lock_prefix) {
        String key = prefix + id.toString();
        // 1. 从redis查询缓存并判断缓存是否命中
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 判断命中是否为空值
        if(json != null) {
            return null;
        }
        String lock = null;
        R r;

        try {
            // 2. 未命中，尝试获取锁进行cache重建
            lock = lock_prefix + id;
            // 未能获取到锁，进行休眠
            if(!this.tryLock(lock)) {
                Thread.sleep(500);
                // 递归查询
                return queryByLock(prefix, id, type, dbFallback, lock_prefix);
            }

            // 若成功获取锁，根据id查询DB
            r = dbFallback.apply(id);

            // 处理缓存穿透问题
            // 若key不存在，将其写入redis，对应空值，设置ttl
            if(r == null) {
                this.set2Redis(key, "", 5L, TimeUnit.MINUTES);
                return null;
            }

            // 3. 将数据写入redis，并设置ttl
            this.set2Redis(key, JSONUtil.toJsonStr(r), 30L, TimeUnit.MINUTES);
        } catch(InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 4. 释放锁
            this.unlock(lock);
        }

        return r;
    }

    /**
     * 逻辑过期时间
     */
    public <R, ID> R queryByLogical(String prefix, ID id, Class<R> type,
                                    Function<ID, R> getById, String lock_prefix) {
        String key = prefix + id.toString();
        // 1. 从redis查询缓存并判断缓存是否命中
        String json = stringRedisTemplate.opsForValue().get(key);
        // 未命中，直接返回null
        if(StrUtil.isBlank(json)) {
            return null;
        }

        // 2. 获取逻辑时间，判断是否过期
        RedisDataDTO redisData = JSONUtil.toBean(json, RedisDataDTO.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime exprireTime = redisData.getExprireTime();
        // 未过期，直接返回
        if(exprireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        // 已过期
        String lock = lock_prefix + id;
        boolean flag = tryLock(lock);

        // 3.若获取到lock，进行缓存重建
        if(flag) {
            // 开启线程进行重建
            try {
                cache_rebuild.submit(() -> prepareHotByRedis(id, 30L, getById));
            } catch(Exception e) {
                throw new RuntimeException(e);
            } finally {
                unlock(lock);
            }
        }

        return r;
    }

    // 线程池
    private static final ExecutorService cache_rebuild = Executors.newCachedThreadPool();

    /**
     * 指定shop和逻辑过期时间
     * 向redis存入该shop
     */
    public <R, ID> void prepareHotByRedis(ID id, Long time, Function<ID, R> get_R) {
        R r = get_R.apply(id);
        String key = "hmdp:shop:cache:" + id.toString();
        set2RedisWithLogical(key, r, time, TimeUnit.MINUTES);
    }

    /**
     * 根据key获取锁
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }




}
