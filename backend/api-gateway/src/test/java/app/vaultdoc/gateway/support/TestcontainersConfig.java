package app.vaultdoc.gateway.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers wiring for any integration test that needs Postgres + Redis.
 * Import via {@code @Import(TestcontainersConfig.class)}.
 *
 * <p>The bean methods deliberately start the containers eagerly via
 * Testcontainers' built-in lifecycle — Spring registers the {@code @ServiceConnection}
 * factories and wires {@code spring.datasource.*} / {@code spring.data.redis.*}
 * automatically.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("vaultdoc")
                .withUsername("test")
                .withPassword("test")
                .withInitScript("init-test.sql");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
                .withExposedPorts(6379)
                .waitingFor(Wait.forListeningPort());
    }
}
