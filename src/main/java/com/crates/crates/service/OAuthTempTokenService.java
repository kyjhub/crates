package com.crates.crates.service;

import com.crates.crates.Global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OAuthTempTokenService {

    private final StringRedisTemplate stringRedisTemplate;

    public String issue(Long userId) {
        String uuid = UUID.randomUUID().toString();
        String key = "oauth:temp:" + uuid;
        stringRedisTemplate.opsForValue().set(key, userId.toString(), Duration.ofMinutes(5));
        return uuid;
    }

    public Long consume(String token) {
        String key = "oauth:temp:" + token;
        // getAndDelete provides atomic operation for get and delete
        String value = stringRedisTemplate.opsForValue().getAndDelete(key);
        
        if (value == null) {
            throw new BusinessException("유효하지 않거나 만료된 임시 토큰입니다.");
        }
        
        return Long.parseLong(value);
    }
}
