package com.familyfinance.reporting;

import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.CurrentHousehold;
import com.familyfinance.shared.RequestValidationException;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportingController {

    private final DashboardService dashboardService;
    private final AnalysisService analysisService;
    private final CurrentHousehold currentHousehold;

    public ReportingController(
            DashboardService dashboardService,
            AnalysisService analysisService,
            CurrentHousehold currentHousehold) {
        this.dashboardService = dashboardService;
        this.analysisService = analysisService;
        this.currentHousehold = currentHousehold;
    }

    @GetMapping("/api/dashboard")
    ApiEnvelope<DashboardResponse> dashboard(
            Authentication authentication,
            @RequestParam(required = false) String month) {
        return ApiEnvelope.data(dashboardService.dashboard(currentHousehold.id(authentication), parseMonth(month)));
    }

    @GetMapping("/api/analysis")
    ApiEnvelope<AnalysisResponse> analysis(
            Authentication authentication,
            @RequestParam(required = false) String month) {
        return ApiEnvelope.data(analysisService.analysis(currentHousehold.id(authentication), parseMonth(month)));
    }

    private static YearMonth parseMonth(String rawMonth) {
        if (rawMonth == null || rawMonth.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(rawMonth.trim());
        } catch (DateTimeParseException exception) {
            throw new RequestValidationException(Map.of("month", "月份格式必须是 YYYY-MM"));
        }
    }
}
