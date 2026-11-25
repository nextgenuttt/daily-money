package com.dailymoney.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BodySizeLimitFilterTest {

    @Test
    void bigBodiesAreRefusedAndNormalOnesPass() throws Exception {
        BodySizeLimitFilter filter = new BodySizeLimitFilter();
        MockHttpServletRequest big = new MockHttpServletRequest("POST", "/api/v1/sync/push");
        big.setContent(new byte[(int) BodySizeLimitFilter.MAX_BYTES + 1]);
        MockHttpServletResponse refused = new MockHttpServletResponse();
        MockFilterChain bigChain = new MockFilterChain();
        filter.doFilter(big, refused, bigChain);
        assertThat(refused.getStatus()).isEqualTo(413);
        assertThat(bigChain.getRequest()).isNull();

        MockHttpServletRequest small = new MockHttpServletRequest("POST", "/api/v1/sync/push");
        small.setContent(new byte[1000]);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(small, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
