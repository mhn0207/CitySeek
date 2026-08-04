package com.cityseek.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
public abstract class ContainerIntegrationTest {

    @Container
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            DockerImageName.parse("mysql:8.0.36"))
            .withDatabaseName("cityseek")
            .withUsername("cityseek")
            .withPassword("cityseek")
            .withInitScript("db/cityseek.sql");

    @Container
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.redis.initialize-cache", () -> "false");
        registry.add("app.redis.stream-consumer", () -> "integration-consumer");
        registry.add("app.redis.pending-min-idle", () -> "100ms");
        registry.add("app.redis.pending-recovery-interval", () -> "100ms");
        registry.add("app.redis.pending-batch-size", () -> "20");
        registry.add("app.redis.stream-max-length", () -> "10000");
        registry.add("app.redis.dlq-max-length", () -> "1000");
        registry.add("app.redis.stream-metrics-interval", () -> "60s");
        registry.add("app.feed.outbox.poll-interval", () -> "100");
        registry.add("app.upload-dir", () -> "target/test-uploads");
        registry.add("logging.level.com.cityseek", () -> "warn");
    }
}
