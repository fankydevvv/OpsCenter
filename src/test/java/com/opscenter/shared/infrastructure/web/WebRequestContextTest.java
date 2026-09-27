package com.opscenter.shared.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** D-30: the audited client IP is the TCP peer; a client-supplied X-Forwarded-For is never trusted here. */
class WebRequestContextTest {

    @Test
    void usesTheRemoteAddress_andIgnoresAForgedForwardedForHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "10.0.0.1, 203.0.113.5");

        assertThat(WebRequestContext.clientIpOf(request)).isEqualTo("192.0.2.10");
    }

    @Test
    void truncatesToTheColumnWidth() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("x".repeat(100));

        assertThat(WebRequestContext.clientIpOf(request)).hasSize(64);
    }
}
