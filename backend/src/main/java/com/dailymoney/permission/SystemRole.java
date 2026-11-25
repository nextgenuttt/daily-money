package com.dailymoney.permission;

import static com.dailymoney.permission.Permission.*;

import java.util.EnumSet;
import java.util.Set;

/**
 * The four roles every organization starts with and their default permissions (LLD §5.3).
 * "Configurable" cells start at the safer default; Phase 6 lets owners change them per role and member.
 */
public enum SystemRole {
    OWNER("Owner", EnumSet.of(
            EXPENSE_VIEW, EXPENSE_ADD, EXPENSE_EDIT_OWN, EXPENSE_DELETE_OWN, EXPENSE_APPROVE, EXPENSE_SPLIT_MANAGE, EXPENSE_REPORT_VIEW,
            EXPENSE_BUDGET_MANAGE, MEMBER_INVITE, MEMBER_MANAGE, PERMISSION_MANAGE, ORGANIZATION_SETTINGS_MANAGE,
            EXPENSE_MESSAGE_SEND, EXPENSE_MESSAGE_RECEIVE, EXPENSE_MESSAGE_REPLY, EXPENSE_NOTIFICATION_RECEIVE,
            EXPENSE_MESSAGE_ATTACH, REMINDER_MANAGE, EVENT_MANAGE, TASK_MANAGE, TASK_ASSIGN, ATTACHMENT_VIEW)),
    ADMIN("Admin", EnumSet.of(
            EXPENSE_VIEW, EXPENSE_ADD, EXPENSE_EDIT_OWN, EXPENSE_DELETE_OWN, EXPENSE_SPLIT_MANAGE, EXPENSE_REPORT_VIEW,
            EXPENSE_BUDGET_MANAGE, MEMBER_INVITE, MEMBER_MANAGE,
            EXPENSE_MESSAGE_SEND, EXPENSE_MESSAGE_RECEIVE, EXPENSE_MESSAGE_REPLY, EXPENSE_NOTIFICATION_RECEIVE,
            EXPENSE_MESSAGE_ATTACH, REMINDER_MANAGE, EVENT_MANAGE, TASK_MANAGE, TASK_ASSIGN, ATTACHMENT_VIEW)),
    MEMBER("Member", EnumSet.of(
            EXPENSE_VIEW, EXPENSE_ADD, EXPENSE_EDIT_OWN, EXPENSE_DELETE_OWN, EXPENSE_SPLIT_MANAGE, EXPENSE_REPORT_VIEW,
            EXPENSE_MESSAGE_SEND, EXPENSE_MESSAGE_RECEIVE, EXPENSE_MESSAGE_REPLY, EXPENSE_NOTIFICATION_RECEIVE,
            EXPENSE_MESSAGE_ATTACH, REMINDER_MANAGE, EVENT_MANAGE, TASK_MANAGE, ATTACHMENT_VIEW)),
    VIEWER("Viewer", EnumSet.of(EXPENSE_VIEW, EXPENSE_MESSAGE_RECEIVE, EXPENSE_NOTIFICATION_RECEIVE, ATTACHMENT_VIEW));

    private final String displayName;
    private final Set<Permission> defaults;

    SystemRole(String displayName, Set<Permission> defaults) {
        this.displayName = displayName;
        this.defaults = defaults;
    }

    public String displayName() {
        return displayName;
    }

    public Set<Permission> defaults() {
        return EnumSet.copyOf(defaults);
    }
}
