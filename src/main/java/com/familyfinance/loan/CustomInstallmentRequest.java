package com.familyfinance.loan;
import java.time.LocalDate;
public record CustomInstallmentRequest(LocalDate dueOn, String principal, String interest) {}
