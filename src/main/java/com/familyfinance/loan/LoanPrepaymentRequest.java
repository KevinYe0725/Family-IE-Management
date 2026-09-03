package com.familyfinance.loan;
import java.time.LocalDate;
public record LoanPrepaymentRequest(String amount, LocalDate paidOn, String idempotencyKey) {}
