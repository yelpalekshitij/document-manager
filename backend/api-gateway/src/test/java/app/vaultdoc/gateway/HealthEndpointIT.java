package app.vaultdoc.gateway;

import app.vaultdoc.gateway.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@TestPropertySource(properties = {
        "vaultdoc.elasticsearch.url=http://localhost:1",
        "vaultdoc.s3.endpoint=http://localhost:1",
        "vaultdoc.s3.access-key=test",
        "vaultdoc.s3.secret-key=test",
        "vaultdoc.s3.bucket=test"
})
class HealthEndpointIT {

    @DynamicPropertySource
    static void overrideRedisPassword(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void healthReportsDbAndRedisUp() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db").value("UP"))
                .andExpect(jsonPath("$.components.redis").value("UP"));
        // S3 and Elasticsearch are intentionally pointed at unreachable hosts; the
        // overall rollup will be DEGRADED but the endpoint itself returns 200.
    }
}
