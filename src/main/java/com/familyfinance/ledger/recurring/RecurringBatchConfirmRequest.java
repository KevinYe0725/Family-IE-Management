package com.familyfinance.ledger.recurring;

import java.util.List;

public record RecurringBatchConfirmRequest(List<Long> occurrenceIds, java.util.Map<Long,String> confirmationTokens) {}
