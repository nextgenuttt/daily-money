package com.dailymoney.sync;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.dailymoney.identity.domain.Device;
import com.dailymoney.identity.infra.DeviceRepository;
import com.dailymoney.organization.domain.Membership;
import com.dailymoney.organization.infra.MembershipRepository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Sends pokes after the change is committed, off the request thread; a failed poke only delays sync. */
@Component
public class SyncPokeSender {

    private static final Logger LOGGER = Logger.getLogger(SyncPokeSender.class.getName());

    private final MembershipRepository memberships;
    private final DeviceRepository devices;
    private final PushGateway push;
    private final TaskExecutor executor;
    private final TransactionTemplate tx;

    public SyncPokeSender(MembershipRepository memberships, DeviceRepository devices, PushGateway push,
            @Qualifier("applicationTaskExecutor") TaskExecutor executor,
            PlatformTransactionManager transactionManager) {
        this.memberships = memberships;
        this.devices = devices;
        this.push = push;
        this.executor = executor;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(SyncPoke poke) {
        executor.execute(() -> {
            try {
                send(poke);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Sync poke failed", e);
            }
        });
    }

    void send(SyncPoke poke) {
        Set<UUID> people = new HashSet<>(poke.userIds());
        if (poke.orgId() != null) {
            memberships.activeInOrg(poke.orgId()).stream().map(Membership::getUserId).forEach(people::add);
        }
        if (people.isEmpty()) return;
        List<Device> targets = devices.findByUserIdIn(people).stream()
                .filter(d -> d.getFcmToken() != null && !d.getId().equals(poke.excludeDevice()))
                .toList();
        if (targets.isEmpty()) return;
        Set<String> invalid = push.sendSyncPoke(targets.stream().map(Device::getFcmToken).distinct().toList());
        if (invalid.isEmpty()) return;
        tx.executeWithoutResult(s -> targets.stream()
                .filter(d -> invalid.contains(d.getFcmToken()))
                .map(d -> devices.findById(d.getId()).orElse(null))
                .filter(Objects::nonNull)
                .forEach(Device::clearPushToken));
    }
}
