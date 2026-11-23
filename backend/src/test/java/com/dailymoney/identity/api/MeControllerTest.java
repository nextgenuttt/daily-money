package com.dailymoney.identity.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.dailymoney.identity.application.AccountService;
import com.dailymoney.identity.application.TokenRevocationService;
import com.dailymoney.identity.domain.User;
import com.dailymoney.shared.security.FirebaseProperties;
import com.dailymoney.shared.security.SecurityConfig;
import com.dailymoney.shared.web.ApiExceptionHandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MeController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
@EnableConfigurationProperties(FirebaseProperties.class)
@TestPropertySource(properties = "app.firebase.project-id=test-project")
class MeControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountService accounts;

    @MockitoBean
    TokenRevocationService revocation;

    @BeforeEach
    void notRevoked() {
        when(revocation.isRevoked(anyString(), any())).thenReturn(false);
    }

    @Test
    void bootstrapWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/me/bootstrap").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bootstrapReturnsProfile() throws Exception {
        User user = User.create(UUID.randomUUID(), "uid-1", "Ravi", Instant.now());
        when(accounts.bootstrap(any(), any())).thenReturn(user);

        mvc.perform(post("/api/v1/me/bootstrap")
                        .with(jwt().jwt(j -> j.subject("uid-1").claim("firebase", Map.of("sign_in_provider", "google.com"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + UUID.randomUUID() + "\",\"deviceName\":\"Pixel 8\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Ravi"))
                .andExpect(jsonPath("$.defaultCurrency").value("INR"));
    }

    @Test
    void bootstrapValidatesDeviceId() throws Exception {
        mvc.perform(post("/api/v1/me/bootstrap")
                        .with(jwt().jwt(j -> j.subject("uid-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void revokedTokenIsRejected() throws Exception {
        when(revocation.isRevoked(anyString(), any())).thenReturn(true);

        mvc.perform(post("/api/v1/auth/logout-all").with(jwt().jwt(j -> j.subject("uid-1"))))
                .andExpect(status().isUnauthorized());
    }
}
