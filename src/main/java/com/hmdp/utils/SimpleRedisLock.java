package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
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

    // 提前初始化lua脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

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
     * 释放锁，使用lua脚本保证原子性
     * 直接使用execute调用脚本，但需要提前定义脚本
     * 需要注意execute的第二个参数
     */
    @Override
    public void unlock() {
        String key = PREFIX_LOCK + name;
        String threadID = ID_PREFIX + Thread.currentThread().getId();
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), threadID);
    }
}
