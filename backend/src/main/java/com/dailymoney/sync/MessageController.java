package com.dailymoney.sync;

import java.util.List;
import java.util.UUID;

import com.dailymoney.identity.application.CurrentUserService;
import com.dailymoney.identity.domain.User;
import com.dailymoney.shared.security.FirebasePrincipal;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MessageController {

    public record ReadRequest(List<UUID> ids) {
    }

    private final MessageService messages;
    private final NotificationService notifications;
    private final SyncStore store;
    private final CurrentUserService currentUser;
    private final ApplicationEventPublisher events;

    public MessageController(MessageService messages, NotificationService notifications, SyncStore store, CurrentUserService currentUser,
            ApplicationEventPublisher events) {
        this.messages = messages;
        this.notifications = notifications;
        this.store = store;
        this.currentUser = currentUser;
        this.events = events;
    }

    @GetMapping("/transactions/{id}/messages")
    public MessageService.ThreadView thread(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return messages.thread(me(jwt), id);
    }

    @PostMapping("/transactions/{id}/messages")
    public MessageService.MessageView post(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody MessageService.NewMessage body) {
        return messages.post(me(jwt), id, body);
    }

    @PostMapping("/transactions/{id}/messages/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        messages.markRead(me(jwt), id);
    }

    @DeleteMapping("/messages/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        messages.delete(me(jwt), id);
    }

    /** No ids = mark everything read. */
    @PutMapping("/notifications/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void readNotifications(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) ReadRequest body) {
        User me = me(jwt);
        store.lockWrites();
        if (notifications.markRead(me.getId(), body == null ? List.of() : body.ids()) > 0) events.publishEvent(SyncPoke.users(me.getId()));
    }

    private User me(Jwt jwt) {
        return currentUser.require(FirebasePrincipal.from(jwt));
    }
}
