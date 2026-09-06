package com.sonhoang2.userservice.auth;

import com.sonhoang2.common.ratelimit.RateLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthRateLimitService {

    private final RateLimitService rateLimitService;
    private final StringRedisTemplate redisTemplate;

    public long checkRateLimit(String identifier) {
        String blockKey = "blocked:auth:" + identifier;
        Long ttl = redisTemplate.getExpire(blockKey, TimeUnit.SECONDS);

        if (ttl != null && ttl > 0) {
            return ttl;
        }

        boolean consumed = rateLimitService.tryConsume(
                "auth:" + identifier, 5, Duration.ofMinutes(1)
        );

        if (!consumed) {
            redisTemplate.opsForValue().set(blockKey, "1", Duration.ofMinutes(15));
            return 15 * 60;
        }
        return 0;
    }
}
