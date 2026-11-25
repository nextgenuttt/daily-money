package com.dailymoney.shared.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** No API request needs more than 1 MB (a full sync push is ~100 KB); bigger bodies are refused unread. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BodySizeLimitFilter extends OncePerRequestFilter {

    static final long MAX_BYTES = 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"status\":413,\"code\":\"TOO_LARGE\",\"detail\":\"Request is too large\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
