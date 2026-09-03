package com.familyfinance.loan;
import com.familyfinance.shared.ApiEnvelope;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/loan-installments")
public class LoanInstallmentController {
 private final LoanInstallmentConfirmationService confirmations;
 LoanInstallmentController(LoanInstallmentConfirmationService confirmations){this.confirmations=confirmations;}
 @PostMapping("/{id}/confirm") ApiEnvelope<LoanInstallmentResponse> confirm(Authentication authentication,@PathVariable long id){return ApiEnvelope.data(confirmations.confirm(authentication,id));}
}
