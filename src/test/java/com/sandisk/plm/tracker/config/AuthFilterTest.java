package com.sandisk.plm.tracker.config;

import org.junit.jupiter.api.Test;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

public class AuthFilterTest {

    /** OBA API is server-to-server with its own X-API-Key gate — the session filter
     *  must let it through, never shadow it with a 401/redirect. */
    @Test
    void obaApiBypassesSessionCheck() throws Exception {
        AuthFilter filter = new AuthFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getRequestURI()).thenReturn("/api/oba/required-docs");

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);                       // passed through to controller
        verify(res, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(req, never()).getSession(anyBoolean());          // session never consulted
    }

    /** Agent API is server-to-server with its own X-API-Key gate — the session
     *  filter must let it through, exactly like OBA. */
    @Test
    void agentApiBypassesSessionCheck() throws Exception {
        AuthFilter filter = new AuthFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getRequestURI()).thenReturn("/api/agent/catalog");

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verify(res, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(req, never()).getSession(anyBoolean());
    }

    /** A normal API path with no session is still rejected — proves the allowlist is
     *  specific, not a blanket open door. */
    @Test
    void unlistedApiStillRequiresSession() throws Exception {
        AuthFilter filter = new AuthFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getRequestURI()).thenReturn("/api/items-search/run");
        when(req.getSession(false)).thenReturn(null);
        when(res.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilter(req, res, chain);

        verify(res).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());
    }
}
