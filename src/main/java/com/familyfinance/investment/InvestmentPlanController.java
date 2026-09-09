package com.familyfinance.investment;

import static com.familyfinance.investment.InvestmentPlanDtos.*;
import com.familyfinance.accounting.AccountingCommandExecutor;
import com.familyfinance.accounting.AccountingRequests;
import com.familyfinance.shared.ApiEnvelope;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/investment-plans")
public class InvestmentPlanController {
    private final InvestmentPlanService service;
    private final AccountingCommandExecutor executor;
    public InvestmentPlanController(InvestmentPlanService service,AccountingCommandExecutor executor){this.service=service;this.executor=executor;}
    @GetMapping
    ApiEnvelope<Page> list(Authentication auth,@RequestParam(defaultValue="0") int planPage,
            @RequestParam(defaultValue="0") int occurrencePage,@RequestParam(defaultValue="20") int size){return ApiEnvelope.data(service.list(auth,planPage,occurrencePage,size));}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<Plan> create(Authentication auth,@RequestBody Request request,@RequestHeader(value="Idempotency-Key",required=false) String supplied){String key=AccountingRequests.key(supplied);return ApiEnvelope.data(executor.execute(()->service.create(auth,request,key)));}
    @PatchMapping("/{id}")
    ApiEnvelope<Plan> update(Authentication auth,@PathVariable long id,@RequestBody Request request){return ApiEnvelope.data(executor.execute(()->service.update(auth,id,request)));}
    @PostMapping("/{id}/state")
    ApiEnvelope<Plan> state(Authentication auth,@PathVariable long id,@RequestBody StateRequest request){return ApiEnvelope.data(executor.execute(()->service.state(auth,id,request)));}
    @PostMapping("/occurrences/{id}/confirm")
    ApiEnvelope<Occurrence> confirm(Authentication auth,@PathVariable long id,@RequestBody Confirmation request){return ApiEnvelope.data(executor.execute(()->service.confirm(auth,id,request)));}
    @PostMapping("/occurrences/{id}/skip")
    ApiEnvelope<Occurrence> skip(Authentication auth,@PathVariable long id,@RequestBody SkipRequest request){return ApiEnvelope.data(executor.execute(()->service.skip(auth,id,request)));}
    @PostMapping("/occurrences/{id}/snooze")
    ApiEnvelope<Occurrence> snooze(Authentication auth,@PathVariable long id,@RequestBody SnoozeRequest request){return ApiEnvelope.data(executor.execute(()->service.snooze(auth,id,request)));}
    @PostMapping("/generate")
    ApiEnvelope<Map<String,Integer>> generate(Authentication auth){return ApiEnvelope.data(Map.of("generated",executor.execute(()->service.generate(auth))));}
}
