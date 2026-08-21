package dev.hookrelay.common.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Lua betiklerini bean olarak yukler.
 *
 * DefaultRedisScript SHA'yi bir kez hesaplar ve sonraki cagrilarda EVALSHA
 * kullanir; betigin govdesi her istekte tel uzerinden gitmez.
 */
@Configuration
public class RedisScripts {

    @Bean
    @SuppressWarnings("unchecked")
    public RedisScript<List> rateLimitScript() {
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource("lua/rate_limit.lua"));
        s.setResultType(List.class);
        return s;
    }

    @Bean
    @SuppressWarnings("unchecked")
    public RedisScript<List> circuitBreakerScript() {
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource("lua/circuit_breaker.lua"));
        s.setResultType(List.class);
        return s;
    }

    @Bean
    @SuppressWarnings("unchecked")
    public RedisScript<List> idempotencyScript() {
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource("lua/idempotency.lua"));
        s.setResultType(List.class);
        return s;
    }
}
