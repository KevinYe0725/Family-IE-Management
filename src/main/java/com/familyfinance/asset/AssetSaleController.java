package com.familyfinance.asset;

import com.familyfinance.accounting.AccountingCommandExecutor;
import com.familyfinance.accounting.AccountingRequests;
import com.familyfinance.shared.ApiEnvelope;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/assets/{id}")
public class AssetSaleController {
    private final AssetSaleService sales;private final AccountingCommandExecutor executor;
    public AssetSaleController(AssetSaleService sales,AccountingCommandExecutor executor){this.sales=sales;this.executor=executor;}
    @PostMapping("/sale-preview")
    ApiEnvelope<AssetSalePreview> preview(Authentication auth,@PathVariable long id,@RequestBody AssetSaleDraft draft){
        return ApiEnvelope.data(sales.preview(auth,id,draft));
    }
    @PostMapping("/sale")
    ApiEnvelope<AssetSaleResult> sell(Authentication auth,@PathVariable long id,@RequestBody AssetSaleRequest request,
            @RequestHeader("Idempotency-Key") String supplied){
        String key=AccountingRequests.key(supplied);
        return ApiEnvelope.data(executor.execute(()->sales.sell(auth,id,request,key)));
    }
    @GetMapping("/sale")
    ReceiptEnvelope receipt(Authentication auth,@PathVariable long id){return new ReceiptEnvelope(sales.receipt(auth,id));}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record ReceiptEnvelope(AssetSaleResult data){}
}
