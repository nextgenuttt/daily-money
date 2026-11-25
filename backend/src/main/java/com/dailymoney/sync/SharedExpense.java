package com.dailymoney.sync;

import java.time.Instant;
import java.util.UUID;

/** One row of `transactions`: an expense or income shared with a group (LLD §4.3). */
public record SharedExpense(
        UUID id,
        UUID orgId,
        UUID creatorId,
        String type,
        long amountMinor,
        String currency,
        String categoryName,
        String categoryIcon,
        Instant occurredAt,
        String note,
        String paymentMethod,
        String visibility,
        String approvalStatus,
        int version,
        long serverSeq,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {

    public static final String ORGANIZATION = "ORGANIZATION";
    public static final String SELECTED_MEMBERS = "SELECTED_MEMBERS";
    public static final String APPROVED = "APPROVED";

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
