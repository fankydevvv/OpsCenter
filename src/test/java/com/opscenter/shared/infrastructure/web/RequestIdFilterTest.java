package com.opscenter.shared.infrastructure.web;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** 04-API §2.2 / D-17: echo a valid client id, generate one otherwise, expose it via MDC during the request only. */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void echoesValidClientRequestIdAndPutsItInMdcDuringTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");
        request.addHeader(RequestIdFilter.HEADER, "client-req.42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) {
                seenInChain.set(MDC.get(RequestIdFilter.MDC_KEY));
            }
        });

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("client-req.42");
        assertThat(seenInChain.get()).isEqualTo("client-req.42");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).as("MDC cleaned after request").isNull();
    }

    @Test
    void generatesUuidWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).matches("[0-9a-f-]{36}");
    }

    @Test
    void replacesUnsafeOrOversizedHeaderValues() {
        assertThat(RequestIdFilter.resolve("bad value with spaces")).matches("[0-9a-f-]{36}");
        assertThat(RequestIdFilter.resolve("<script>")).matches("[0-9a-f-]{36}");
        assertThat(RequestIdFilter.resolve("x".repeat(101))).matches("[0-9a-f-]{36}");
        assertThat(RequestIdFilter.resolve("ok_ID-1.2")).isEqualTo("ok_ID-1.2");
    }
}
