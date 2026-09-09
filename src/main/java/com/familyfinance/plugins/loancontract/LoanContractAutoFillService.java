package com.familyfinance.plugins.loancontract;

import com.familyfinance.loan.LoanType;
import com.familyfinance.loan.RepaymentMethod;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Orchestrates loan-contract auto-fill:
 * <ul>
 *   <li>{@code useAi == true}: the server-managed system AI is the primary extractor; the
 *       deterministic rule extractor only fills fields the AI did not return, and is the whole
 *       fallback when the AI call fails.</li>
 *   <li>{@code useAi == false}: the rule extractor is used as today (no external call).</li>
 * </ul>
 * The AI is never invoked without the caller supplying the user's consent for that extraction.
 */
@Service
public class LoanContractAutoFillService {
    private static final String AI_FALLBACK_WARNING = "系统 AI 未能完成本次提取，已回退到规则识别（可尝试重新上传）。";

    private final LoanContractExtractionService ruleExtraction;
    private final AiLoanContractExtractor aiExtraction;

    public LoanContractAutoFillService(LoanContractExtractionService ruleExtraction, AiLoanContractExtractor aiExtraction) {
        this.ruleExtraction = ruleExtraction;
        this.aiExtraction = aiExtraction;
    }

    public LoanContractExtractionResponse extract(Authentication auth, boolean useAi, MultipartFile file) {
        return extractFromText(auth, useAi, ruleExtraction.extractText(file), file.getOriginalFilename());
    }

    /** Orchestrates over already-extracted normalized text (kept public for offline tests). */
    public LoanContractExtractionResponse extractFromText(
            Authentication auth, boolean useAi, String normalized, String filename) {
        LoanContractExtractionResponse fallback = ruleExtraction.extractFromText(normalized, filename);
        if (!useAi) return fallback;
        Optional<AiLoanContractExtractor.Extraction> read = aiExtraction.read(auth, normalized);
        if (read.isEmpty()) return withWarning(fallback, AI_FALLBACK_WARNING);
        return merge(read.get(), fallback);
    }

    /** AI-supplied values win; the rule result backfills any field the AI left null. */
    private static LoanContractExtractionResponse merge(
            AiLoanContractExtractor.Extraction ai, LoanContractExtractionResponse fallback) {
        LoanContractFields f = fallback.fields();
        RepaymentMethod method = ai.repaymentMethod() != null ? ai.repaymentMethod() : f.repaymentMethod();
        LocalDate startOn = ai.startOn() != null ? LocalDate.parse(ai.startOn()) : f.startOn();
        LoanType type = ai.loanType() != null ? ai.loanType() : f.loanType();
        String principal = ai.principal() != null ? ai.principal() : f.principal();
        String rate = ai.annualRatePercent() != null ? ai.annualRatePercent() : f.annualRatePercent();
        Integer term = ai.termMonths() != null ? ai.termMonths() : f.termMonths();

        Map<String, Double> confidence = new LinkedHashMap<>(fallback.confidence());
        if (ai.loanType() != null) confidence.put("loanType", 0.98);
        if (ai.principal() != null) confidence.put("principal", 0.98);
        if (ai.annualRatePercent() != null) confidence.put("annualRatePercent", 0.98);
        if (ai.termMonths() != null) confidence.put("termMonths", 0.98);
        if (ai.startOn() != null) confidence.put("startOn", 0.98);
        if (ai.repaymentMethod() != null) confidence.put("repaymentMethod", 0.98);

        List<String> warnings = new ArrayList<>();
        if (principal == null) warnings.add("未识别到贷款本金，请人工填写");
        if (rate == null) warnings.add("未识别到年利率，请人工填写");
        if (term == null) warnings.add("未识别到贷款期限，请人工填写");
        if (startOn == null) warnings.add("未识别到起息日，请人工填写");

        LoanContractFields merged = new LoanContractFields(
                f.suggestedName(), type, principal, rate, term, method, startOn);
        return new LoanContractExtractionResponse(fallback.documentName(), merged, confidence, warnings);
    }

    private static LoanContractExtractionResponse withWarning(LoanContractExtractionResponse response, String warning) {
        List<String> warnings = new ArrayList<>();
        if (response.warnings() != null) warnings.addAll(response.warnings());
        warnings.add(warning);
        return new LoanContractExtractionResponse(
                response.documentName(), response.fields(), response.confidence(), List.copyOf(warnings));
    }
}
