package com.familyfinance.ledger.recurring;

import com.familyfinance.shared.ApiEnvelope;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RecurringController {
    private final RecurringService service;
    private final RecurringConfirmationService confirmationService;

    public RecurringController(RecurringService service, RecurringConfirmationService confirmationService) {
        this.service = service;
        this.confirmationService = confirmationService;
    }

    @GetMapping("/api/recurring-rules")
    ResponseEntity<ApiEnvelope<List<RecurringRuleResponse>>> listRules(
            Authentication authentication,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        RecurringRulePage result = service.listRules(authentication, includeInactive, page, size);
        return page(result.items(), result.page(), result.size(), result.totalElements(),
                result.totalPages(), result.hasNext());
    }

    @PostMapping("/api/recurring-rules")
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<RecurringRuleResponse> create(
            Authentication authentication, @RequestBody RecurringRuleRequest request) {
        return ApiEnvelope.data(service.create(authentication, request));
    }

    @PatchMapping("/api/recurring-rules/{id}")
    ApiEnvelope<RecurringRuleResponse> update(
            Authentication authentication,
            @PathVariable long id,
            @RequestBody RecurringRulePatchRequest request) {
        return ApiEnvelope.data(service.update(authentication, id, request));
    }

    @DeleteMapping("/api/recurring-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(Authentication authentication, @PathVariable long id) {
        service.archive(authentication, id);
    }

    @GetMapping("/api/recurring-occurrences")
    ResponseEntity<ApiEnvelope<List<RecurringOccurrenceResponse>>> listOccurrences(
            Authentication authentication,
            @RequestParam(required = false) RecurringOccurrenceStatus status,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Long assignedUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        RecurringOccurrencePage result = service.listOccurrences(
                authentication, status, from, to, assignedUserId, page, size);
        return page(result.items(), result.page(), result.size(), result.totalElements(),
                result.totalPages(), result.hasNext());
    }

    @PostMapping("/api/recurring-occurrences/{id}/confirm")
    ApiEnvelope<RecurringOccurrenceResponse> confirm(
            Authentication authentication, @PathVariable long id) {
        return ApiEnvelope.data(confirmationService.confirm(authentication, id));
    }

    private static <T> ResponseEntity<ApiEnvelope<List<T>>> page(
            List<T> items, int page, int size, long totalElements, int totalPages, boolean hasNext) {
        return ResponseEntity.ok()
                .header("X-Page", Integer.toString(page))
                .header("X-Page-Size", Integer.toString(size))
                .header("X-Total-Elements", Long.toString(totalElements))
                .header("X-Total-Pages", Integer.toString(totalPages))
                .header("X-Has-Next", Boolean.toString(hasNext))
                .body(ApiEnvelope.data(items));
    }
}
