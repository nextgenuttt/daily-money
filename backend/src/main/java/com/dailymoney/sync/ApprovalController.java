package com.dailymoney.sync;

import java.util.UUID;

import com.dailymoney.identity.application.CurrentUserService;
import com.dailymoney.identity.domain.User;
import com.dailymoney.shared.security.FirebasePrincipal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ApprovalController {

    public record DecisionRequest(int version, String reason) {
    }

    private final ApprovalService approvals;
    private final CurrentUserService currentUser;

    public ApprovalController(ApprovalService approvals, CurrentUserService currentUser) {
        this.approvals = approvals;
        this.currentUser = currentUser;
    }

    @GetMapping("/organizations/{id}/approval-policy")
    public ApprovalService.PolicyView policy(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return approvals.policy(me(jwt), id);
    }

    @PutMapping("/organizations/{id}/approval-policy")
    public ApprovalService.PolicyView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody ApprovalService.PolicyInput body) {
        return approvals.updatePolicy(me(jwt), id, body);
    }

    @PostMapping("/transactions/{id}/approve")
    public ApprovalService.DecisionResult approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody DecisionRequest body) {
        return approvals.approve(me(jwt), id, body.version());
    }

    @PostMapping("/transactions/{id}/reject")
    public ApprovalService.DecisionResult reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody DecisionRequest body) {
        return approvals.reject(me(jwt), id, body.version(), body.reason());
    }

    private User me(Jwt jwt) {
        return currentUser.require(FirebasePrincipal.from(jwt));
    }
}
