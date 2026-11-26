package com.dailymoney.identity.infra;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.dailymoney.identity.application.FirebaseAccountAdmin;
import com.dailymoney.shared.security.FirebaseProperties;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.SendResponse;
import com.dailymoney.sync.PushGateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FirebaseAdminConfig {

    private static final Logger LOGGER = Logger.getLogger(FirebaseAdminConfig.class.getName());

    @Bean
    FirebaseAccountAdmin firebaseAccountAdmin(FirebaseProperties properties) throws IOException {
        FirebaseApp app = app(properties);
        if (app == null) {
            LOGGER.warning("FIREBASE_SERVICE_ACCOUNT_FILE is not set: sign-out-everywhere only rejects current "
                    + "tokens and account deletion will not remove the Firebase user");
            return new DisabledAdmin();
        }
        return new SdkAdmin(FirebaseAuth.getInstance(app));
    }

    @Bean
    PushGateway pushGateway(FirebaseProperties properties) throws IOException {
        FirebaseApp app = app(properties);
        if (app == null) {
            LOGGER.warning("FIREBASE_SERVICE_ACCOUNT_FILE is not set: other phones sync only when opened, not instantly");
            return tokens -> Set.of();
        }
        return new FcmPushGateway(FirebaseMessaging.getInstance(app));
    }

    /**
     * A key file locally; on Google Cloud (Cloud Run) set FIREBASE_SERVICE_ACCOUNT_FILE=google to use the
     * service's own identity instead, so no key file is ever uploaded.
     */
    private static synchronized FirebaseApp app(FirebaseProperties properties) throws IOException {
        String file = properties.serviceAccountFile();
        if (file == null || file.isBlank()) return null;
        if (!FirebaseApp.getApps().isEmpty()) return FirebaseApp.getInstance();
        GoogleCredentials credentials;
        if (USE_PLATFORM_IDENTITY.equals(file)) {
            credentials = GoogleCredentials.getApplicationDefault();
        } else {
            try (InputStream in = new FileInputStream(file)) {
                credentials = GoogleCredentials.fromStream(in);
            }
        }
        FirebaseOptions options = FirebaseOptions.builder().setCredentials(credentials).setProjectId(properties.projectId()).build();
        return FirebaseApp.initializeApp(options);
    }

    private static final String USE_PLATFORM_IDENTITY = "google";

    /** Data-only, collapsible: many changes while a phone is asleep wake it once. */
    private static final class FcmPushGateway implements PushGateway {

        private static final int BATCH = 500;

        private final FirebaseMessaging messaging;

        FcmPushGateway(FirebaseMessaging messaging) {
            this.messaging = messaging;
        }

        // The app still registers FCM tokens; moving both sides to installation IDs (setFid) is a follow-up.
        @SuppressWarnings("deprecation")
        @Override
        public Set<String> sendSyncPoke(List<String> tokens) {
            Set<String> invalid = new HashSet<>();
            for (int from = 0; from < tokens.size(); from += BATCH) {
                List<String> batch = tokens.subList(from, Math.min(tokens.size(), from + BATCH));
                AndroidConfig android = AndroidConfig.builder().setCollapseKey("sync").setTtl(Duration.ofDays(1).toMillis()).build();
                List<Message> messages = batch.stream()
                        .map(token -> Message.builder().setToken(token).putData("type", "SYNC").setAndroidConfig(android).build())
                        .toList();
                try {
                    List<SendResponse> responses = messaging.sendEach(messages).getResponses();
                    for (int i = 0; i < responses.size(); i++) {
                        SendResponse r = responses.get(i);
                        if (!r.isSuccessful() && r.getException() != null
                                && r.getException().getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
                            invalid.add(batch.get(i));
                        }
                    }
                } catch (FirebaseMessagingException e) {
                    LOGGER.log(Level.WARNING, "FCM sync poke failed", e);
                }
            }
            return invalid;
        }
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
