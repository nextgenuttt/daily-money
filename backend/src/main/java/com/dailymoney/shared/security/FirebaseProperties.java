package com.dailymoney.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.firebase")
public record FirebaseProperties(String projectId, String serviceAccountFile) {

    public String issuer() {
        return "https://securetoken.google.com/" + projectId;
    }
}
