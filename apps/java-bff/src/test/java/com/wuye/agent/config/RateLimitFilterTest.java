package com.wuye.agent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    @Test
    void thirdRequestIsRateLimited() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new ObjectMapper(), 2);
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = doFilter(filter);
            assertEquals(200, response.getStatus());
        }
        MockHttpServletResponse limited = doFilter(filter);
        assertEquals(429, limited.getStatus());
        assertEquals("60", limited.getHeader("Retry-After"));
    }

    private MockHttpServletResponse doFilter(RateLimitFilter filter) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
