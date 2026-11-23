package com.dailymoney.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Full application against a real PostgreSQL (Flyway migrations, JPA validation, SQL constraints).
 * One database per test JVM; tests use fresh random users so they don't interfere.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    private static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new IllegalStateException("Could not start embedded PostgreSQL", e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("app.firebase.project-id", () -> "test-project");
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    /** A signed-in Google user (email verified) with a bootstrapped profile. */
    protected TestUser newUser(String name) throws Exception {
        String uid = "uid-" + UUID.randomUUID();
        String email = name.toLowerCase().replace(' ', '.') + "-" + uid.substring(4, 12) + "@example.com";
        TestUser user = new TestUser(uid, email, name);
        mvc.perform(post("/api/v1/me/bootstrap").with(user.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + UUID.randomUUID() + "\",\"displayName\":\"" + name + "\"}"))
                .andExpect(status().isOk());
        return user;
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    protected MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        return builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    public record TestUser(String uid, String email, String name) {
        public RequestPostProcessor auth() {
            return jwt().jwt(j -> j.subject(uid)
                    .claim("email", email)
                    .claim("email_verified", true)
                    .claim("name", name)
                    .claim("firebase", Map.of("sign_in_provider", "google.com")));
        }
    }
}
