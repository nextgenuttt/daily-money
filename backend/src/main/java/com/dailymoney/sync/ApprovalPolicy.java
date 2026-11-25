package com.dailymoney.sync;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** A group's approval settings (LLD §4.5). Groups without a saved policy are INSTANT. */
public record ApprovalPolicy(
        String mode,
        boolean allowSelfApproval,
        int requiredApprovals,
        boolean reapproveOnMaterialChange,
        int version,
        List<Rule> rules,
        Set<UUID> approvers) {

    public static final String INSTANT = "INSTANT";
    public static final String REQUIRE_APPROVAL = "REQUIRE_APPROVAL";
    public static final String CONDITIONAL = "CONDITIONAL";
    public static final String PENDING = "PENDING_APPROVAL";
    public static final String REJECTED = "REJECTED";

    public static ApprovalPolicy instant() {
        return new ApprovalPolicy(INSTANT, false, 1, true, 0, List.of(), Set.of());
    }

    /** One condition; a CONDITIONAL group asks for approval when any enabled rule matches. */
    public record Rule(UUID id, int priority, String type, Long amountMinor, List<String> categories, List<UUID> userIds, boolean enabled) {

        public static final String AMOUNT_GTE = "AMOUNT_GTE";
        public static final String CATEGORY_IN = "CATEGORY_IN";
        public static final String CREATOR_IN = "CREATOR_IN";

        boolean matches(SharedExpense e) {
            return switch (type) {
                case AMOUNT_GTE -> amountMinor != null && e.amountMinor() >= amountMinor;
                // Categories are names typed on each phone, so compare without case.
                case CATEGORY_IN -> e.categoryName() != null && categories != null
                        && categories.stream().anyMatch(c -> c.trim().toLowerCase(Locale.ROOT).equals(e.categoryName().trim().toLowerCase(Locale.ROOT)));
                case CREATOR_IN -> userIds != null && userIds.contains(e.creatorId());
                default -> false;
            };
        }
    }

    /** LLD §7 evaluate(): the status a new or resubmitted expense starts in. */
    public String evaluate(SharedExpense e) {
        return switch (mode) {
            case REQUIRE_APPROVAL -> PENDING;
            case CONDITIONAL -> rules.stream().filter(Rule::enabled).anyMatch(r -> r.matches(e)) ? PENDING : SharedExpense.APPROVED;
            default -> SharedExpense.APPROVED;
        };
    }
}
