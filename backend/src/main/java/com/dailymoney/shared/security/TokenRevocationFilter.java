package com.dailymoney.shared.security;

import java.io.IOException;
import java.time.Instant;

import com.dailymoney.identity.application.TokenRevocationService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects ID tokens issued before the user's "sign out everywhere" time. Firebase ID tokens stay
 * valid for up to an hour after their refresh tokens are revoked, so this closes that window.
 */
public class TokenRevocationFilter extends OncePerRequestFilter {

    private final TokenRevocationService revocationService;

    public TokenRevocationFilter(TokenRevocationService revocationService) {
        this.revocationService = revocationService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            Instant issuedAt = token.getToken().getIssuedAt();
            if (revocationService.isRevoked(token.getToken().getSubject(), issuedAt)) {
                SecurityContextHolder.clearContext();
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token revoked");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
