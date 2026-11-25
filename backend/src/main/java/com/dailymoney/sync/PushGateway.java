package com.dailymoney.sync;

import java.util.List;
import java.util.Set;

/** Sends data-only FCM messages. Content never travels in the push; the phone pulls it over HTTPS. */
public interface PushGateway {

    /** Sends `{type: SYNC}` to each token and returns the tokens FCM says are no longer valid. */
    Set<String> sendSyncPoke(List<String> tokens);
}
