package com.familyfinance.household;

import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.CurrentHousehold;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members")
public class MemberController {

    private final MemberService memberService;
    private final CurrentHousehold currentHousehold;

    public MemberController(MemberService memberService, CurrentHousehold currentHousehold) {
        this.memberService = memberService;
        this.currentHousehold = currentHousehold;
    }

    @GetMapping
    ApiEnvelope<List<MemberResponse>> list(Authentication authentication) {
        return ApiEnvelope.data(memberService.list(currentHousehold.id(authentication)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<MemberResponse> create(Authentication authentication, @Valid @RequestBody MemberRequest request) {
        return ApiEnvelope.data(memberService.create(currentHousehold.id(authentication), request));
    }

    @PatchMapping("/{id}")
    ApiEnvelope<MemberResponse> update(
            Authentication authentication,
            @PathVariable long id,
            @Valid @RequestBody MemberRequest request) {
        return ApiEnvelope.data(memberService.update(currentHousehold.id(authentication), id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Authentication authentication, @PathVariable long id) {
        memberService.delete(currentHousehold.id(authentication), id);
    }
}
