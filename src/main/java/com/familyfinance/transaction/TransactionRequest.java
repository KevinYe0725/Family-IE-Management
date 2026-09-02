package com.familyfinance.transaction;

import com.familyfinance.category.TransactionKind;
import jakarta.validation.constraints.Size;

public record TransactionRequest(
        TransactionKind kind,
        String amount,
        String occurredOn,
        Long accountId,
        Long memberId,
        Long categoryId,
        @Size(max = 100, message = "商家长度不能超过 100 个字符")
        String merchant,
        @Size(max = 100, message = "地点长度不能超过 100 个字符")
        String location,
        @Size(max = 500, message = "备注长度不能超过 500 个字符")
        String note) {
}
