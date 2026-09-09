package com.familyfinance.transaction;

import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.accounting.AccountingCommandExecutor;
import com.familyfinance.accounting.AccountingRequests;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final TransactionSummaryService transactionSummaryService;
    private final CurrentHousehold currentHousehold;
    private final AccountingCommandExecutor executor;

    public TransactionController(
            TransactionService transactionService,
            TransactionSummaryService transactionSummaryService,
            CurrentHousehold currentHousehold,
            AccountingCommandExecutor executor) {
        this.transactionService = transactionService;
        this.transactionSummaryService = transactionSummaryService;
        this.currentHousehold = currentHousehold;
        this.executor=executor;
    }

    @GetMapping
    ResponseEntity<ApiEnvelope<List<TransactionResponse>>> list(
            Authentication authentication,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long bankAccountId,
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        TransactionFilter filter = filter(month, from, to, kind, accountId, bankAccountId, memberId, categoryId, q);
        TransactionPage result = transactionService.list(currentHousehold.id(authentication), filter, page, size);
        return ResponseEntity.ok()
                .header("X-Page", Integer.toString(result.page()))
                .header("X-Page-Size", Integer.toString(result.size()))
                .header("X-Total-Elements", Long.toString(result.totalElements()))
                .header("X-Total-Pages", Integer.toString(result.totalPages()))
                .header("X-Has-Next", Boolean.toString(result.hasNext()))
                .body(ApiEnvelope.data(result.items()));
    }

    @GetMapping("/summary")
    ApiEnvelope<TransactionSummaryResponse> summary(
            Authentication authentication,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long bankAccountId,
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String q) {
        TransactionFilter filter = filter(month, from, to, kind, accountId, bankAccountId, memberId, categoryId, q);
        return ApiEnvelope.data(transactionSummaryService.summarize(currentHousehold.id(authentication), filter));
    }

    @GetMapping("/{id}")
    ApiEnvelope<TransactionResponse> get(Authentication authentication, @PathVariable long id) {
        return ApiEnvelope.data(transactionService.get(currentHousehold.id(authentication), id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<TransactionResponse> create(
            Authentication authentication,
            @Valid @RequestBody TransactionRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String suppliedKey) {
        String key=AccountingRequests.key(suppliedKey);
        return ApiEnvelope.data(executor.execute(()->transactionService.create(authentication, request,key)));
    }

    @PatchMapping("/{id}")
    ApiEnvelope<TransactionResponse> update(
            Authentication authentication,
            @PathVariable long id,
            @Valid @RequestBody TransactionPatchRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String suppliedKey) {
        String key=AccountingRequests.key(suppliedKey);
        return ApiEnvelope.data(executor.execute(()->transactionService.update(authentication, id, request,key)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Authentication authentication, @PathVariable long id,
            @RequestHeader(value="Idempotency-Key",required=false) String suppliedKey) {
        String key=AccountingRequests.key(suppliedKey);
        executor.execute(()->{transactionService.delete(authentication, id,key);return null;});
    }

    private static TransactionFilter filter(
            String month,
            String from,
            String to,
            String kind,
            Long accountId,
            Long bankAccountId,
            Long memberId,
            Long categoryId,
            String q) {
        return new TransactionFilter(month, from, to, kind, accountId, memberId, categoryId, q, bankAccountId);
    }
}
