package com.dailymoney.sync;

import java.util.Set;
import java.util.UUID;

/**
 * "Something changed, sync now" for other phones (LLD §6.6). Published inside a write transaction and
 * sent only after it commits. [orgId] wakes every active member; [userIds] wakes specific people.
 */
public record SyncPoke(UUID orgId, Set<UUID> userIds, UUID excludeDevice) {

    public static SyncPoke org(UUID orgId, UUID excludeDevice) {
        return new SyncPoke(orgId, Set.of(), excludeDevice);
    }

    public static SyncPoke org(UUID orgId) {
        return new SyncPoke(orgId, Set.of(), null);
    }

    public static SyncPoke users(UUID... userIds) {
        return new SyncPoke(null, Set.of(userIds), null);
    }
}
