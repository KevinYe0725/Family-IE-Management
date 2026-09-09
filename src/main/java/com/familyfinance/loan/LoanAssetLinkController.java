package com.familyfinance.loan;

import com.familyfinance.accounting.AccountingCommandExecutor;
import com.familyfinance.accounting.AccountingRequests;
import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.RequestValidationException;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/loans")
public class LoanAssetLinkController {
    private final LoanAssetLinkService links;
    private final AccountingCommandExecutor executor;

    public LoanAssetLinkController(LoanAssetLinkService links,AccountingCommandExecutor executor) {this.links=links;this.executor=executor;}

    @PutMapping("/{id}/asset-link")
    ApiEnvelope<LoanResponse> update(Authentication authentication,@PathVariable long id,@RequestBody LoanAssetLinkRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String supplied) {
        if(supplied==null)throw new RequestValidationException(Map.of("idempotencyKey","请提供本次请求键"));
        String key=AccountingRequests.key(supplied);
        return ApiEnvelope.data(executor.execute(()->links.update(authentication,id,request,key)));
    }
}
