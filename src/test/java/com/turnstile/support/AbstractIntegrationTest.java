package com.turnstile.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real Postgres in a container for every integration test. No H2, no mocks.
 *
 * <p>The whole project turns on exact Postgres locking semantics under
 * READ COMMITTED. An in-memory database with different concurrency behaviour
 * would let broken code pass, which is worse than no test at all.
 *
 * <p>The container is a static singleton started once per JVM and shared by
 * every subclass, rather than a per-class {@code @Container}. Starting Postgres
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

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
