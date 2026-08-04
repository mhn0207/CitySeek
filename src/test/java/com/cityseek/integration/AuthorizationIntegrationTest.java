package com.cityseek.integration;

import com.cityseek.security.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.cityseek.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class AuthorizationIntegrationTest extends ContainerIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void onlyAdministratorCanCreateShop() throws Exception {
        seedSession("normal-user", 2L, UserRole.USER);
        seedSession("administrator", 1L, UserRole.ADMIN);
        String body = """
                {"name":"authorized shop","typeId":1,"images":"/shop.jpg",
                 "address":"authorized address","x":120.1,"y":30.2,
                 "sold":999,"comments":999,"score":50}
                """;

        assertEquals(403, postShop("normal-user", body).statusCode());
        HttpResponse<String> adminResponse = postShop("administrator", body);
        assertEquals(200, adminResponse.statusCode());
        long shopId = objectMapper.readTree(adminResponse.body()).path("data").asLong();
        Map<String, Object> statistics = jdbcTemplate.queryForMap(
                "SELECT sold, comments, score FROM tb_shop WHERE id = ?", shopId);
        assertEquals(0, ((Number) statistics.get("sold")).intValue());
        assertEquals(0, ((Number) statistics.get("comments")).intValue());
        assertEquals(0, ((Number) statistics.get("score")).intValue());
    }

    private HttpResponse<String> postShop(String token, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/shop"))
                .header("Authorization", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void seedSession(String token, long userId, UserRole role) {
        redisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, Map.of(
                "id", Long.toString(userId),
                "nickName", "integration-user-" + userId,
                "icon", "",
                "role", role.name()));
        redisTemplate.expire(LOGIN_USER_KEY + token, 30, TimeUnit.MINUTES);
    }
}
