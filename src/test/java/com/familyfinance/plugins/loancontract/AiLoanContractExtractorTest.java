package com.familyfinance.plugins.loancontract;

import com.familyfinance.ai.AiGateway;
import com.familyfinance.loan.LoanType;
import com.familyfinance.loan.RepaymentMethod;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AiLoanContractExtractorTest {

    private final Authentication auth = new UsernamePasswordAuthenticationToken("demo@local.family", "x");

    @Test
    void rejectsUnitBearingAndExponentAmountsInsteadOfChangingTheirMeaning() {
        for (String amount : java.util.List.of("120万元", "1.2万", "￥1,200", "1e1000000")) {
            var extractor = new AiLoanContractExtractor(fake("{\"principal\":\""+amount+"\",\"termMonths\":12}"), new JsonMapper());
            assertThat(extractor.read(auth, "合同").orElseThrow().principal()).as(amount).isNull();
        }
    }

    @Test
    void parsesModelJsonIntoNormalizedFieldsEvenWhenWrappedInFences() {
        String modelReply = "```json\n{\"principal\":\"1200000\",\"annualRatePercent\":\"4.20\","
                + "\"termMonths\":300,\"startOn\":\"2026-09-08\","
                + "\"loanType\":\"MORTGAGE\",\"repaymentMethod\":\"EQUAL_PAYMENT\"}\n```";
        var extractor = new AiLoanContractExtractor(fake(modelReply), new JsonMapper());

        Optional<AiLoanContractExtractor.Extraction> result =
                extractor.read(auth, "个人住房贷款合同 贷款本金 壹佰贰拾万元整");

        assertThat(result).isPresent();
        var e = result.get();
        assertThat(e.principal()).isEqualTo("1200000");
        assertThat(e.annualRatePercent()).isEqualTo("4.20");
        assertThat(e.termMonths()).isEqualTo(300);
        assertThat(e.startOn()).isEqualTo("2026-09-08");
        assertThat(e.repaymentMethod()).isEqualTo(RepaymentMethod.EQUAL_PAYMENT);
        assertThat(e.loanType()).isEqualTo(LoanType.MORTGAGE);
    }

    @Test
    void treatsMissingAndMalformedValuesAsNull() {
        String modelReply = "{\"principal\":\"五十万\",\"annualRatePercent\":\"4.2\",\"termMonths\":9999,"
                + "\"startOn\":\"not-a-date\",\"loanType\":\"BOGUS\",\"repaymentMethod\":null}";
        var extractor = new AiLoanContractExtractor(fake(modelReply), new JsonMapper());

        var result = extractor.read(auth, "合同文本");

        assertThat(result).isPresent();
        var e = result.get();
        assertThat(e.principal()).isNull();          // 中文金额无法当纯数字
        assertThat(e.annualRatePercent()).isEqualTo("4.2");
        assertThat(e.termMonths()).isNull();         // 超过 360 个月判为非法
        assertThat(e.startOn()).isNull();
        assertThat(e.loanType()).isNull();
        assertThat(e.repaymentMethod()).isNull();
    }

    @Test
    void returnsEmptyWhenGatewayFailsOrReplyIsNotJson() {
        var extractor = new AiLoanContractExtractor(gatewayThatThrows(), new JsonMapper());
        assertThat(extractor.read(auth, "文本")).isEmpty();

        var notJson = new AiLoanContractExtractor(fake("抱歉，无法识别该文档。"), new JsonMapper());
        assertThat(notJson.read(auth, "文本")).isEmpty();
    }

    @Test
    void capsSentExcerptAndKeepsWholePromptWithinGatewayLimit() {
        AtomicReference<AiGateway.Prompt> captured = new AtomicReference<>();
        var extractor = new AiLoanContractExtractor((a, prompt) -> {
            captured.set(prompt);
            return "{}";
        }, new JsonMapper());

        String huge = "贷款本金：500000.00元\n年利率：4.2%\n".repeat(3000); // ~100k chars
        extractor.read(auth, huge);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().userApprovedExternalProcessing()).isTrue();
        assertThat(captured.get().text().length()).isLessThanOrEqualTo(8000);
    }

    private static AiGateway fake(String reply) {
        return (a, prompt) -> reply;
    }

    private static AiGateway gatewayThatThrows() {
        return (a, prompt) -> {
            throw new IllegalStateException("system AI not configured");
        };
    }
}
