package com.familyfinance.loan;
import com.familyfinance.shared.ApiEnvelope; import org.springframework.http.*; import org.springframework.security.core.Authentication; import org.springframework.web.bind.annotation.*; import java.util.List;
@RestController @RequestMapping("/api/loans") public class LoanController { private final LoanService loans; private final LoanPrepaymentService prepayments; LoanController(LoanService loans,LoanPrepaymentService prepayments){this.loans=loans;this.prepayments=prepayments;}
 @GetMapping ResponseEntity<ApiEnvelope<LoanPage>> list(Authentication a,@RequestParam(defaultValue="ACTIVE") LoanStatus status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){LoanPage p=loans.list(a,status,page,size);return ResponseEntity.ok().header("X-Page",String.valueOf(p.page())).header("X-Page-Size",String.valueOf(p.size())).header("X-Has-Next",String.valueOf(p.hasNext())).body(ApiEnvelope.data(p));}
 @GetMapping("/{id}") ApiEnvelope<LoanResponse> get(Authentication a,@PathVariable long id){return ApiEnvelope.data(loans.get(a,id));}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) ApiEnvelope<LoanResponse> create(Authentication a,@RequestBody LoanCreateRequest r){return ApiEnvelope.data(loans.create(a,r));}
 @PatchMapping("/{id}") ApiEnvelope<LoanResponse> update(Authentication a,@PathVariable long id,@RequestBody LoanPatchRequest r){return ApiEnvelope.data(loans.update(a,id,r));}
 @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void archive(Authentication a,@PathVariable long id){loans.archive(a,id);}
 @GetMapping("/{id}/schedule") ApiEnvelope<List<LoanInstallmentResponse>> schedule(Authentication a,@PathVariable long id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return ApiEnvelope.data(loans.schedule(a,id,page,size));}
 @PostMapping("/{id}/prepay") ApiEnvelope<LoanPrepaymentResponse> prepay(Authentication a,@PathVariable long id,@RequestBody LoanPrepaymentRequest r){return ApiEnvelope.data(prepayments.prepay(a,id,r));}
}
