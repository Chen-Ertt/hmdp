package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;


/**
 * 这个Redis锁的实现类不交由Spring管理
 * 因为需要该Util类的复用，也就是每一个线程使用不同的该类的对象
 */
public class SimpleRedisLock implements ILock {
    // 锁名
    private final String name;
    private final StringRedisTemplate stringRedisTemplate;

    // 锁的前缀
    private static final String PREFIX_LOCK = "lock:";

    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 创建锁
     * key = lock:name
     * value = UUID-线程ID
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        String key = PREFIX_LOCK + name;
        String threadID = ID_PREFIX + Thread.currentThread().getId();
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, threadID, timeoutSec, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁，需要判断value（UUID+线程ID）是否一致
     */
    @Override
    public void unlock() {
        String key = PREFIX_LOCK + name;
        String threadID = ID_PREFIX + Thread.currentThread().getId();
        String id = stringRedisTemplate.opsForValue().get(key);
        if(threadID.equals(id)) {
            stringRedisTemplate.delete(key);
        }
    }
}
