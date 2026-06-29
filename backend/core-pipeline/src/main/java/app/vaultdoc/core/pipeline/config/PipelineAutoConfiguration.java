package app.vaultdoc.core.pipeline.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import app.vaultdoc.core.pipeline.stream.RedisStreamPublisher;
import app.vaultdoc.core.pipeline.stream.StreamPublisher;

@Configuration
public class PipelineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    StreamPublisher streamPublisher(StringRedisTemplate redis) {
        return new RedisStreamPublisher(redis);
    }
}
