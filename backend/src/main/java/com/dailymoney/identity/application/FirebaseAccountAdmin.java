package com.dailymoney.identity.application;

/** Server-side Firebase Auth actions (needs the Firebase service account). */
public interface FirebaseAccountAdmin {

    /** Stops every device from getting new ID tokens. */
    void revokeRefreshTokens(String firebaseUid);

    void deleteUser(String firebaseUid);
}
