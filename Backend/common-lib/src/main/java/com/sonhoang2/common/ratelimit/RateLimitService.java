package com.sonhoang2.common.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final LettuceBasedProxyManager<byte[]> proxyManager;

    public boolean tryConsume(String key, int points, Duration duration) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        Bucket bucket = proxyManager.builder().build(keyBytes, () ->
                BucketConfiguration.builder()
                        .addLimit(Bandwidth.classic(points, Refill.greedy(points, duration)))
                        .build()
        );
        return bucket.tryConsume(1);
    }
}
