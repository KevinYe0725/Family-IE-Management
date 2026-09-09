package com.familyfinance.budget;

import com.familyfinance.shared.ApiEnvelope;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/budget-templates")
public class BudgetTemplateController {

    private final BudgetTemplateService templates;

    public BudgetTemplateController(BudgetTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping
    ApiEnvelope<List<BudgetTemplateResponse>> list(Authentication authentication) {
        return ApiEnvelope.data(templates.list(authentication));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<BudgetTemplateResponse> create(
            Authentication authentication,
            @RequestBody BudgetTemplateCreateRequest request) {
        return ApiEnvelope.data(templates.create(authentication, request));
    }

    @PostMapping("/{id}/apply")
    ApiEnvelope<BudgetTemplateApplyResponse> apply(
            Authentication authentication,
            @PathVariable long id,
            @RequestParam String periodMonth) {
        return ApiEnvelope.data(templates.apply(authentication, id, periodMonth));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Authentication authentication, @PathVariable long id) {
        templates.delete(authentication, id);
    }
}
