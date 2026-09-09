package com.familyfinance.plugins.loancontract;

import com.familyfinance.ai.AiGateway;
import com.familyfinance.loan.LoanType;
import com.familyfinance.loan.RepaymentMethod;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class LoanContractAutoFillServiceTest {
    private final Authentication auth = new UsernamePasswordAuthenticationToken("demo@local.family", "x");

    private static final String MORTGAGE = String.join("\n",
            "个人住房按揭贷款合同",
            "1.甲方向乙方借款人民币（大写）壹佰贰拾万元整（¥1,200,000.00元），规定用于购买自住住房。",
            "2.借款期限约定为25年（共300个月），即从2026年09月08日起。",
            "3.按年利率4.20%（折合月利率0.35%）计算。",
            "4.还款方式为：等额本息还款法。");

    private final LoanContractAutoFillService service =
            new LoanContractAutoFillService(new LoanContractExtractionService(), extractor(""));

    @Test
    void withoutAiConsentUsesOnlyTheRuleExtractor() {
        var result = service.extractFromText(auth, false, MORTGAGE, "合同.docx");
        assertThat(result.fields().principal()).isEqualTo("1200000");
        assertThat(result.warnings()).doesNotContain("系统 AI 未能完成本次提取，已回退到规则识别（可尝试重新上传）。");
    }

    @Test
    void aiValuesWinWhenConsentedAndSuccessful() {
        var autofill = new LoanContractAutoFillService(new LoanContractExtractionService(),
                extractor("{\"principal\":\"1500000.00\",\"annualRatePercent\":\"4.5\",\"termMonths\":360,"
                        + "\"startOn\":\"2025-01-01\",\"loanType\":\"MORTGAGE\",\"repaymentMethod\":\"EQUAL_PRINCIPAL\"}"));
        var result = autofill.extractFromText(auth, true, MORTGAGE, "合同.docx");
        assertThat(result.fields().principal()).isEqualTo("1500000.00");
        assertThat(result.fields().annualRatePercent()).isEqualTo("4.5");
        assertThat(result.fields().termMonths()).isEqualTo(360);
        assertThat(result.fields().startOn()).hasToString("2025-01-01");
        assertThat(result.fields().repaymentMethod()).isEqualTo(RepaymentMethod.EQUAL_PRINCIPAL);
        assertThat(result.fields().loanType()).isEqualTo(LoanType.MORTGAGE);
        assertThat(result.confidence().get("principal")).isGreaterThan(0.9);
    }

    @Test
    void fallsBackToRulesWhenAiFailsAndFlagsIt() {
        var autofill = new LoanContractAutoFillService(new LoanContractExtractionService(),
                new AiLoanContractExtractor((a, prompt) -> { throw new IllegalStateException("boom"); }, new JsonMapper()));
        var result = autofill.extractFromText(auth, true, MORTGAGE, "合同.docx");
        assertThat(result.fields().principal()).isEqualTo("1200000"); // 规则兜底仍正确
        assertThat(result.warnings()).anyMatch(w -> w.contains("系统 AI"));
    }

    private static AiLoanContractExtractor extractor(String reply) {
        return new AiLoanContractExtractor((a, prompt) -> reply, new JsonMapper());
    }

    @Test
    void preservesCorrectRuleAmountAndConfidenceWhenModelAmountContainsUnits() {
        var rules = new LoanContractExtractionService();
        var expected = rules.extractFromText(MORTGAGE, "合同.docx");
        var result = new LoanContractAutoFillService(rules, extractor("{\"principal\":\"120万元\",\"termMonths\":300}"))
                .extractFromText(auth, true, MORTGAGE, "合同.docx");
        assertThat(result.fields().principal()).isEqualTo("1200000");
        assertThat(result.confidence().get("principal")).isEqualTo(expected.confidence().get("principal"));
    }
}
