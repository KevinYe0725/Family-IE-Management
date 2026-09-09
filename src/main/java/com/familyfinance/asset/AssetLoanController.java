package com.familyfinance.asset;

import com.familyfinance.shared.ApiEnvelope;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/assets")
public class AssetLoanController {
    private final AssetLoanReadService loans;

    public AssetLoanController(AssetLoanReadService loans) {this.loans=loans;}

    @GetMapping("/{id}/loans")
    ApiEnvelope<AssetLoanResponse> get(Authentication authentication,@PathVariable long id) {return ApiEnvelope.data(loans.get(authentication,id));}
}
