package com.familyfinance.reporting;

import com.familyfinance.shared.CurrentHousehold;
import com.familyfinance.transaction.TransactionFilter;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExportController {

    private static final MediaType CSV_UTF8 = MediaType.parseMediaType("text/csv;charset=UTF-8");

    private final CsvExportService csvExportService;
    private final CurrentHousehold currentHousehold;

    public ExportController(CsvExportService csvExportService, CurrentHousehold currentHousehold) {
        this.csvExportService = csvExportService;
        this.currentHousehold = currentHousehold;
    }

    @GetMapping(value = "/api/export.csv", produces = "text/csv;charset=UTF-8")
    ResponseEntity<byte[]> export(
            Authentication authentication,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String q) {
        TransactionFilter filter = new TransactionFilter(month, from, to, kind, accountId, memberId, categoryId, q);
        byte[] body = csvExportService.export(currentHousehold.id(authentication), filter);
        return ResponseEntity.ok()
                .contentType(CSV_UTF8)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("family-finance.csv")
                        .build()
                        .toString())
                .body(body);
    }
}
