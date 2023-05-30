package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;


/**
 * 使用Redis，并基于雪花算法生成唯一的ID标识码
 */
@Component
public class RedisIDWorker {
    // 开始的时间戳
    private static final Long BEGIN_TIMESTAMP = 1672531200L;

    // 序列号的位数
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public Long nextID(String keyPrefix) {
        // 生成时间戳
        long now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long time = now - BEGIN_TIMESTAMP;

        // 生成机器序列码
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 拼接返回
        return time << COUNT_BITS | count;
    }

}
