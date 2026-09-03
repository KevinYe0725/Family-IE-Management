package com.familyfinance.loan; import java.util.List;
public record LoanPage(List<LoanResponse> items,int page,int size,long totalElements,int totalPages,boolean hasNext) {}
