package com.familyfinance.family;

import com.familyfinance.shared.ApiEnvelope;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
public class InviteController {
    private final InviteService invites;
    InviteController(InviteService invites) { this.invites = invites; }
    @PostMapping("/api/family/invites") @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<InviteService.CreatedInvite> create(Authentication authentication, @RequestBody(required = false) InviteRequest request) {
        return ApiEnvelope.data(invites.create(authentication, request == null ? null : request.maxUses(), request == null ? null : request.role()));
    }
    @GetMapping("/api/family/invites") ApiEnvelope<InviteService.InvitePage> list(Authentication authentication, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return ApiEnvelope.data(invites.list(authentication, page, size)); }
    @DeleteMapping("/api/family/invites/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(Authentication authentication, @PathVariable long id) { invites.revoke(authentication, id); }
    record InviteRequest(Integer maxUses, HouseholdRole role) {}
}
