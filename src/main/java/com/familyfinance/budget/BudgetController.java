package com.familyfinance.budget;

import com.familyfinance.shared.ApiEnvelope;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/budgets")
public class BudgetController {
    private final BudgetService budgets;
    private final BudgetUsageService usage;
    private final BudgetTotalService totals;

    public BudgetController(BudgetService budgets, BudgetUsageService usage, BudgetTotalService totals) {
        this.budgets = budgets;
        this.usage = usage;
        this.totals = totals;
    }

    @GetMapping
    ResponseEntity<ApiEnvelope<List<BudgetResponse>>> list(
            Authentication authentication,
            @RequestParam(required = false) String periodMonth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        BudgetPage result = budgets.list(authentication, periodMonth, page, size);
        return paged(result.page(), result.size(), result.totalElements(), result.totalPages(), result.hasNext(),
                ApiEnvelope.data(result.items()));
    }

    @GetMapping("/total")
    ApiEnvelope<BudgetTotalResponse> total(
            Authentication authentication,
            @RequestParam String periodMonth) {
        return ApiEnvelope.data(totals.get(authentication, periodMonth));
    }

    @GetMapping("/expense-summary")
    ApiEnvelope<java.util.Map<String, String>> expenseSummary(
            Authentication authentication, @RequestParam String periodMonth) {
        return ApiEnvelope.data(usage.expenseSummary(authentication, BudgetService.requireMonth(periodMonth)));
    }

    @PutMapping("/total")
    ApiEnvelope<BudgetTotalResponse> saveTotal(
            Authentication authentication,
            @RequestBody BudgetTotalRequest request) {
        return ApiEnvelope.data(totals.save(authentication, request));
    }

    @GetMapping("/{id}")
    ApiEnvelope<BudgetResponse> get(Authentication authentication, @PathVariable long id) {
        return ApiEnvelope.data(budgets.get(authentication, id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<BudgetResponse> create(Authentication authentication, @RequestBody BudgetCreateRequest request) {
        return ApiEnvelope.data(budgets.create(authentication, request));
    }

    @GetMapping("/usage")
    ResponseEntity<ApiEnvelope<List<BudgetUsageResponse>>> usage(
            Authentication authentication,
            @RequestParam String periodMonth,
            @RequestParam(defaultValue = "true") boolean rollupCategories,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        BudgetUsagePage result = usage.usage(
                authentication, BudgetService.requireMonth(periodMonth), rollupCategories, includeInactive, active, page, size);
        return paged(result.page(), result.size(), result.totalElements(), result.totalPages(), result.hasNext(),
                ApiEnvelope.data(result.items()));
    }

    @PatchMapping("/{id}")
    ApiEnvelope<BudgetResponse> update(
            Authentication authentication, @PathVariable long id, @RequestBody BudgetPatchRequest request) {
        return ApiEnvelope.data(budgets.update(authentication, id, request));
    }

    @GetMapping("/{id}/revisions")
    ResponseEntity<ApiEnvelope<List<BudgetRevisionResponse>>> revisions(
            Authentication authentication,
            @PathVariable long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        BudgetRevisionPage result = budgets.revisions(authentication, id, page, size);
        return paged(result.page(), result.size(), result.totalElements(), result.totalPages(), result.hasNext(),
                ApiEnvelope.data(result.items()));
    }

    @GetMapping("/{id}/usage-entries")
    ResponseEntity<ApiEnvelope<List<BudgetUsageEntryResponse>>> usageEntries(
            Authentication authentication,
            @PathVariable long id,
            @RequestParam(defaultValue = "true") boolean rollupCategories,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        BudgetUsageEntryPage result = usage.usageEntries(authentication, id, page, size, rollupCategories);
        return paged(result.page(), result.size(), result.totalElements(), result.totalPages(), result.hasNext(),
                ApiEnvelope.data(result.items()));
    }

    @PostMapping("/copy")
    ApiEnvelope<BudgetCopyResponse> copy(
            Authentication authentication,
            @RequestParam String fromMonth,
            @RequestParam String toMonth,
            @RequestParam(defaultValue = "all") String scope) {
        return ApiEnvelope.data(budgets.copy(authentication, fromMonth, toMonth, scope));
    }

    @GetMapping("/hit-check")
    ApiEnvelope<List<BudgetHitResponse>> hitCheck(
            Authentication authentication,
            @RequestParam String periodMonth,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long memberId,
            @RequestParam long amountCents) {
        return ApiEnvelope.data(usage.hitCheck(authentication,
                BudgetService.requireMonth(periodMonth), categoryId, memberId, amountCents));
    }

    @GetMapping("/export.csv")
    ResponseEntity<String> exportCsv(
            Authentication authentication,
            @RequestParam String periodMonth,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        String csv = usage.exportCsv(authentication, BudgetService.requireMonth(periodMonth), includeInactive);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=budget-" + periodMonth + ".csv")
                .body(csv);
    }

    static <T> ResponseEntity<T> paged(
            int page, int size, long totalElements, int totalPages, boolean hasNext, T body) {
        return ResponseEntity.ok()
                .header("X-Page", Integer.toString(page))
                .header("X-Page-Size", Integer.toString(size))
                .header("X-Total-Elements", Long.toString(totalElements))
                .header("X-Total-Pages", Integer.toString(totalPages))
                .header("X-Has-Next", Boolean.toString(hasNext))
                .body(body);
    }
}
