package com.opscenter.servicecatalog.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.opscenter.servicecatalog.application.ServiceCatalogProperties;
import com.opscenter.servicecatalog.application.ServiceResolutionCache.Entry;
import com.opscenter.shared.infrastructure.redis.RedisAvailability;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** D-52: value format of the resolution cache and its fail-open behaviour. */
class RedisServiceResolutionCacheTest {

    @Test
    void encodeDecode_roundTrips_includingNamesWithPipes_andTheNegativeEntry() {
        Entry full = new Entry(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Odoo | ERP");
        Entry noEnvironment = new Entry(UUID.randomUUID(), null, null, null);

        assertThat(RedisServiceResolutionCache.decode(RedisServiceResolutionCache.encode(full))).isEqualTo(full);
        assertThat(RedisServiceResolutionCache.decode(RedisServiceResolutionCache.encode(noEnvironment))).isEqualTo(noEnvironment);
        assertThat(RedisServiceResolutionCache.encode(Entry.NONE)).isEqualTo("NONE");
        assertThat(RedisServiceResolutionCache.decode("NONE")).isEqualTo(Entry.NONE);
        assertThat(RedisServiceResolutionCache.field(null)).isEqualTo("_NONE_");
        assertThat(RedisServiceResolutionCache.key(UUID.fromString("00000000-0000-4000-8000-000000000001"), "odoo-erp"))
                .isEqualTo("opscenter:svc-resolve:v1:00000000-0000-4000-8000-000000000001:odoo-erp");
    }

    @Test
    void redisDown_isAMiss_andWritesAreSkippedSilently() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForHash()).thenThrow(new RedisConnectionFailureException("connection refused"));
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("connection refused"));
        RedisServiceResolutionCache cache = new RedisServiceResolutionCache(redis, availability(), properties(true), Clock.systemUTC());
        UUID org = UUID.randomUUID();

        assertThat(cache.get(org, "odoo-erp", "DEV")).isEmpty();
        assertThatCode(() -> cache.put(org, "odoo-erp", "DEV", Entry.NONE)).doesNotThrowAnyException();
        assertThatCode(() -> cache.evict(org, "odoo-erp")).doesNotThrowAnyException();
    }

    @Test
    void disabledCache_neverTouchesRedisOnRead() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisServiceResolutionCache cache = new RedisServiceResolutionCache(redis, availability(), properties(false), Clock.systemUTC());

        assertThat(cache.get(UUID.randomUUID(), "odoo-erp", "DEV")).isEmpty();
        cache.put(UUID.randomUUID(), "odoo-erp", "DEV", Entry.NONE);
        verifyNoInteractions(redis);
    }

    @Test
    void afterOneRedisFailure_theCacheSkipsRedisForTheCoolDown() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForHash()).thenThrow(new RedisConnectionFailureException("connection refused"));
        RedisAvailability availability = availability();
        RedisServiceResolutionCache cache = new RedisServiceResolutionCache(redis, availability, properties(true),
                Clock.systemUTC());
        UUID org = UUID.randomUUID();

        assertThat(cache.get(org, "odoo-erp", "DEV")).isEmpty();
        assertThat(availability.isAvailable()).isFalse();
        cache.get(org, "odoo-erp", "DEV");
        cache.put(org, "odoo-erp", "DEV", Entry.NONE);

        verify(redis, times(1)).opsForHash();
    }

    private static RedisAvailability availability() {
        return new RedisAvailability(Clock.systemUTC(), Duration.ofSeconds(30));
    }

    private static ServiceCatalogProperties properties(boolean enabled) {
        return new ServiceCatalogProperties(new ServiceCatalogProperties.Resolution(List.of("service"), List.of("env"),
                enabled, Duration.ofMinutes(10), Duration.ofSeconds(30)));
    }
}
