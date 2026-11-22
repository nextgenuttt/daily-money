package com.dailymoney.identity.infra;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.dailymoney.identity.application.FirebaseAccountAdmin;
import com.dailymoney.shared.security.FirebaseProperties;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FirebaseAdminConfig {

    private static final Logger LOGGER = Logger.getLogger(FirebaseAdminConfig.class.getName());

    @Bean
    FirebaseAccountAdmin firebaseAccountAdmin(FirebaseProperties properties) throws IOException {
        String file = properties.serviceAccountFile();
        if (file == null || file.isBlank()) {
            LOGGER.warning("FIREBASE_SERVICE_ACCOUNT_FILE is not set: sign-out-everywhere only rejects current "
                    + "tokens and account deletion will not remove the Firebase user");
            return new DisabledAdmin();
        }
        FirebaseApp app;
        try (InputStream in = new FileInputStream(file)) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(in))
                    .setProjectId(properties.projectId())
                    .build();
            app = FirebaseApp.getApps().isEmpty() ? FirebaseApp.initializeApp(options) : FirebaseApp.getInstance();
        }
        return new SdkAdmin(FirebaseAuth.getInstance(app));
    }

    private static final class SdkAdmin implements FirebaseAccountAdmin {

        private final FirebaseAuth auth;

        SdkAdmin(FirebaseAuth auth) {
            this.auth = auth;
        }

        @Override
        public void revokeRefreshTokens(String firebaseUid) {
            try {
                auth.revokeRefreshTokens(firebaseUid);
            } catch (FirebaseAuthException e) {
                throw new IllegalStateException("Could not revoke Firebase tokens", e);
            }
        }

        @Override
        public void deleteUser(String firebaseUid) {
            try {
                auth.deleteUser(firebaseUid);
            } catch (FirebaseAuthException e) {
                throw new IllegalStateException("Could not delete Firebase user", e);
            }
        }
    }

    private static final class DisabledAdmin implements FirebaseAccountAdmin {

        @Override
        public void revokeRefreshTokens(String firebaseUid) {
            LOGGER.log(Level.WARNING, "Firebase Admin not configured; refresh tokens not revoked for {0}", firebaseUid);
        }

        @Override
        public void deleteUser(String firebaseUid) {
            LOGGER.log(Level.WARNING, "Firebase Admin not configured; Firebase user {0} not deleted", firebaseUid);
        }
    }
}
