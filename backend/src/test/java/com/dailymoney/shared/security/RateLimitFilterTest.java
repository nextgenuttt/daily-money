package com.dailymoney.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bucketAllowsABurstThenRefillsOverTime() {
        double perNano = 60 / 60e9; // one a second
        RateLimitFilter.Bucket bucket = new RateLimitFilter.Bucket(2);
        assertThat(bucket.take(0, perNano, 2)).isZero();
        assertThat(bucket.take(0, perNano, 2)).isZero();
        assertThat(bucket.take(0, perNano, 2)).isEqualTo(1_000_000_000L);
        assertThat(bucket.take(1_000_000_000L, perNano, 2)).isZero();
    }

    @Test
    void eachUserHasTheirOwnLimitAndGets429WithRetryAfter() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, 2);
        assertThat(call(filter, "ravi")).isEqualTo(200);
        assertThat(call(filter, "ravi")).isEqualTo(200);
        MockHttpServletResponse limited = response(filter, "ravi");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotNull();
        assertThat(limited.getContentAsString()).contains("RATE_LIMITED");
        assertThat(call(filter, "asha")).isEqualTo(200);
    }

    private int call(RateLimitFilter filter, String user) throws Exception {
        return response(filter, user).getStatus();
    }

    private MockHttpServletResponse response(RateLimitFilter filter, String user) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(user, null, "ROLE_USER"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/me"), res, new MockFilterChain());
        return res;
    }
}
