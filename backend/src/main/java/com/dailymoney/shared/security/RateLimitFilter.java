package com.dailymoney.shared.security;

import java.io.IOException;
import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-user token bucket (HLD §8 abuse): a burst of [burst] requests, refilled at [perMinute] a minute.
 * Runs after authentication, so the key is the Firebase uid; unauthenticated requests are rejected
 * before this. In-process only: with more than one instance, move the buckets to Redis (HLD §5).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final double ratePerNano;
    private final double burst;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();

    public RateLimitFilter(int perMinute, int burst) {
        this.ratePerNano = perMinute / 60e9;
        this.burst = burst;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        long waitNanos = buckets.get(auth.getName(), k -> new Bucket(burst)).take(System.nanoTime(), ratePerNano, burst);
        if (waitNanos == 0) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(Math.max(1, (waitNanos + 999_999_999) / 1_000_000_000)));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"status\":429,\"code\":\"RATE_LIMITED\",\"detail\":\"Too many requests, try again shortly\"}");
    }

    static final class Bucket {
        private double tokens;
        private long last = -1;

        Bucket(double tokens) {
            this.tokens = tokens;
        }

        /** 0 when a token was taken, otherwise how long until one is available. */
        synchronized long take(long now, double ratePerNano, double burst) {
            if (last >= 0) tokens = Math.min(burst, tokens + (now - last) * ratePerNano);
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - tokens) / ratePerNano);
        }
    }
}
