package com.turnstile.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres and real Redis in containers for every integration test.
 * No H2, no embedded Redis, no mocks.
 *
 * <p>The whole design turns on exact Postgres locking semantics under READ
 * COMMITTED and on Redis executing scripts atomically. A substitute with different
 * concurrency behaviour would let broken code pass, which is worse than no test.
 *
 * <p>Both containers are static singletons started once per JVM and shared by
 * every subclass, rather than per-class {@code @Container} fields - starting them
 * once instead of once per test class keeps the suite fast.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("turnstile")
                    .withUsername("turnstile")
                    .withPassword("turnstile");

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * Wipes Redis before every test. Availability marks carry a five minute TTL,
     * so without this a mark left by one test would silently shed traffic in the
     * next one and produce a baffling failure several classes later.
     *
     * <p>Runs before any subclass {@code @BeforeEach}, which is where table
     * seeding happens.
     */
    @BeforeEach
    void flushRedis() {
        redisTemplate.execute((RedisConnection connection) -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }
}
