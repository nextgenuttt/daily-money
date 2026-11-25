package com.dailymoney.sync;

import java.time.Instant;
import java.util.UUID;

/** Money one member paid another to settle shared costs (LLD §4.4, §8.3). */
public record Settlement(
        UUID id,
        UUID orgId,
        UUID payerId,
        UUID receiverId,
        long amountMinor,
        String currency,
        String method,
        String status,
        String note,
        UUID createdBy,
        Instant createdAt,
        UUID decidedBy,
        Instant decidedAt,
        long serverSeq) {

    public static final String REQUESTED = "REQUESTED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String CANCELLED = "CANCELLED";
}
