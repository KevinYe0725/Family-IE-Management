package com.familyfinance.investment;

import com.familyfinance.shared.ApiEnvelope;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/securities")
public class SecurityController {

    private final SecurityService securities;

    public SecurityController(SecurityService securities) {
        this.securities = securities;
    }

    @GetMapping("/search")
    ResponseEntity<ApiEnvelope<SecurityPage>> search(
            Authentication authentication,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        SecurityPage result = securities.search(authentication, q, page, size);
        return ResponseEntity.ok()
                .header("X-Page", Integer.toString(result.page()))
                .header("X-Page-Size", Integer.toString(result.size()))
                .header("X-Total-Elements", Long.toString(result.totalElements()))
                .header("X-Total-Pages", Integer.toString(result.totalPages()))
                .header("X-Has-Next", Boolean.toString(result.hasNext()))
                .body(ApiEnvelope.data(result));
    }

    @PostMapping("/resolve")
    ApiEnvelope<SecurityResponse> resolve(
            Authentication authentication, @RequestBody SecurityResolveRequest request) {
        return ApiEnvelope.data(securities.resolve(authentication, request));
    }
}
