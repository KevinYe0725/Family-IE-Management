package com.familyfinance.plugins.loancontract;

import com.familyfinance.ai.AiGateway;
import com.familyfinance.loan.LoanType;
import com.familyfinance.loan.RepaymentMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI-augmented loan-contract extraction.
 *
 * Sends a bounded text excerpt (up to {@value #MAX_EXCERPT} chars so the whole prompt stays
 * within the shared AI gateway's 8000-char text limit) to the server-managed system Qwen and
 * asks for a strict JSON object of the six loan fields. Any gateway / parse failure yields
 * {@link Optional#empty()} so callers can fall back to the deterministic rule extractor.
 * Business flow decides consent and never calls this without the user approving the external call.
 */
@Component
public class AiLoanContractExtractor {
    static final int MAX_EXCERPT = 6000;

    private final AiGateway gateway;
    private final ObjectMapper mapper;

    public AiLoanContractExtractor(AiGateway gateway, ObjectMapper mapper) {
        this.gateway = gateway;
        this.mapper = mapper;
    }

    /** The extracted, semantically normalized field values (null where absent/unparsable). */
    public record Extraction(
            String principal,
            String annualRatePercent,
            Integer termMonths,
            String startOn,
            RepaymentMethod repaymentMethod,
            LoanType loanType) {
    }

    /** Attempts an AI extraction of {@code text} for {@code auth}; never throws on failure. */
    public Optional<Extraction> read(Authentication auth, String text) {
        if (auth == null || text == null || text.isBlank()) return Optional.empty();
        String excerpt = text.length() <= MAX_EXCERPT ? text : text.substring(0, MAX_EXCERPT);
        String prompt = prompt(excerpt);
        String raw;
        try {
            raw = gateway.complete(auth, new AiGateway.Prompt(prompt, true));
        } catch (RuntimeException exception) {
            return Optional.empty(); // not configured / rate limited / connection failed -> fallback
        }
        return parse(raw);
    }

    private static String prompt(String excerpt) {
        StringBuilder b = new StringBuilder(MAX_EXCERPT + 800);
        b.append("你是一名中文贷款合同信息抽取助手。请从下面给出的合同文本片段中抽取贷款字段，")
                .append("并且只输出一个 JSON 对象（不要用代码块或 ```json 包裹，不要任何解释）。字段与取值规则：\n")
                .append("- principal：贷款本金，纯数字字符串（单位换算为元，不带千分位，不带“万元”），例如 \"1200000\" 或 \"500000.00\"\n")
                .append("- annualRatePercent：年利率的百分比数值字符串（不含 % 号），例如 \"4.20\"\n")
                .append("- termMonths：贷款期限，单位是月，输出整数\n")
                .append("- startOn：起息日/放款日，ISO 日期 yyyy-MM-dd\n")
                .append("- loanType：只能是 MORTGAGE、CAR 或 OTHER\n")
                .append("- repaymentMethod：只能是 EQUAL_PAYMENT、EQUAL_PRINCIPAL 或 CUSTOM\n")
                .append("无法确定的字段置为 null。金额务必取合同“借款本金/贷款本金”，不要取成“每月偿还贷款本息”。合同文本：\n")
                .append(excerpt);
        return b.toString();
    }

    private Optional<Extraction> parse(String raw) {
        if (raw == null) return Optional.empty();
        String json = raw;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return Optional.empty();
        json = raw.substring(start, end + 1);
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (Exception exception) {
            return Optional.empty();
        }
        if (node == null || !node.isObject()) return Optional.empty();
        return Optional.of(new Extraction(
                money(node, "principal"),
                percent(node, "annualRatePercent"),
                integer(node, "termMonths"),
                date(node, "startOn"),
                method(node, "repaymentMethod"),
                type(node, "loanType")));
    }

    private static String money(JsonNode node, String key) {
        String v = text(node, key);
        if (v == null) return null;
        // The model contract is a plain yuan amount. Never discard unit multipliers.
        if (!v.matches("[0-9]{1,15}(?:\\.[0-9]{1,2})?")) return null;
        String cleaned = v;
        try {
            BigDecimal bd = new BigDecimal(cleaned);
            return bd.signum() > 0 ? bd.toPlainString() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String percent(JsonNode node, String key) {
        String v = text(node, key);
        if (v == null) return null;
        String cleaned = v.replace("%", "").trim();
        if (!cleaned.matches("[0-9]{1,3}(?:\\.[0-9]{1,8})?")) return null;
        try {
            BigDecimal bd = new BigDecimal(cleaned);
            return bd.signum() >= 0 ? bd.toPlainString() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Integer integer(JsonNode node, String key) {
        String v = text(node, key);
        if (v == null) return null;
        try {
            int n = Integer.parseInt(v.trim());
            return n > 0 && n <= 360 ? n : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String date(JsonNode node, String key) {
        String v = text(node, key);
        if (v == null) return null;
        try {
            return LocalDate.parse(v.trim()).toString();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static RepaymentMethod method(JsonNode node, String key) {
        String v = text(node, key);
        if (v == null) return null;
        try {
            return RepaymentMethod.valueOf(v.trim());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static LoanType type(JsonNode node, String key) {
        String v = text(node, key);
        if (v == null) return null;
        try {
            return LoanType.valueOf(v.trim());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return null;
        String s = value.asText("");
        return s == null || s.isBlank() ? null : s.trim();
    }
}
