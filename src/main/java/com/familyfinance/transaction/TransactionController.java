package com.familyfinance.transaction;

import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.CurrentHousehold;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final CurrentHousehold currentHousehold;

    public TransactionController(TransactionService transactionService, CurrentHousehold currentHousehold) {
        this.transactionService = transactionService;
        this.currentHousehold = currentHousehold;
    }

    @GetMapping
    ResponseEntity<ApiEnvelope<List<TransactionResponse>>> list(
            Authentication authentication,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        TransactionFilter filter = new TransactionFilter(month, from, to, kind, accountId, memberId, categoryId, q);
        TransactionPage result = transactionService.list(currentHousehold.id(authentication), filter, page, size);
        return ResponseEntity.ok()
                .header("X-Page", Integer.toString(result.page()))
                .header("X-Page-Size", Integer.toString(result.size()))
                .header("X-Total-Elements", Long.toString(result.totalElements()))
                .header("X-Total-Pages", Integer.toString(result.totalPages()))
                .header("X-Has-Next", Boolean.toString(result.hasNext()))
                .body(ApiEnvelope.data(result.items()));
    }

    @GetMapping("/{id}")
    ApiEnvelope<TransactionResponse> get(Authentication authentication, @PathVariable long id) {
        return ApiEnvelope.data(transactionService.get(currentHousehold.id(authentication), id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<TransactionResponse> create(
            Authentication authentication,
            @Valid @RequestBody TransactionRequest request) {
        return ApiEnvelope.data(transactionService.create(authentication, request));
    }

    @PatchMapping("/{id}")
    ApiEnvelope<TransactionResponse> update(
            Authentication authentication,
            @PathVariable long id,
            @Valid @RequestBody TransactionPatchRequest request) {
        return ApiEnvelope.data(transactionService.update(authentication, id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Authentication authentication, @PathVariable long id) {
        transactionService.delete(authentication, id);
    }
}
