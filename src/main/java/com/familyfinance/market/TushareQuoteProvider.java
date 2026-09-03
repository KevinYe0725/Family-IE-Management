package com.familyfinance.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class TushareQuoteProvider implements MarketQuoteProvider {

    private static final Pattern SYMBOL = Pattern.compile("^[0-9]{6}\\.(SH|SZ|BJ)$");
    private static final List<String> FIELDS = List.of(
            "ts_code", "trade_date", "open", "high", "low", "close", "pre_close", "pct_chg");
    private static final long MAX_CENTS = 99_999_999_999L;

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String token;

    public TushareQuoteProvider(RestClient.Builder builder, ObjectMapper objectMapper,
            @Value("${TUSHARE_TOKEN:}") String token) {
        this.client = builder.baseUrl("https://api.tushare.pro").build();
        this.objectMapper = objectMapper;
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public boolean available() {
        return !token.isBlank();
    }

    @Override
    public List<DailyQuote> fetchDaily(Set<String> symbols) {
        if (!available()) throw new MarketProviderException("MARKET_DISABLED", false);
        List<String> normalized = symbols.stream().map(TushareQuoteProvider::normalize)
                .distinct().sorted(Comparator.naturalOrder()).toList();
        if (normalized.isEmpty()) return List.of();
        Map<String, Object> payload = Map.of(
                "api_name", "daily",
                "token", token,
                "params", Map.of("ts_code", String.join(",", normalized)),
                "fields", String.join(",", FIELDS));
        try {
            String body = client.post().uri("").body(payload).retrieve().body(String.class);
            return parse(body, normalized);
        } catch (RestClientResponseException exception) {
            HttpStatusCode status = exception.getStatusCode();
            throw new MarketProviderException("MARKET_UPSTREAM_UNAVAILABLE",
                    status.value() == 429 || status.is5xxServerError());
        } catch (MarketProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MarketProviderException("MARKET_UPSTREAM_UNAVAILABLE", false);
        }
    }

    private List<DailyQuote> parse(String body, List<String> requested) {
        try {
            JsonNode root = objectMapper.readTree(body);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                throw new MarketProviderException(code == 2002 ? "MARKET_PERMISSION_DENIED" : "MARKET_UPSTREAM_ERROR", false);
            }
            JsonNode data = root.path("data");
            JsonNode fields = data.path("fields");
            JsonNode items = data.path("items");
            if (!fields.isArray() || !items.isArray() || fields.size() != FIELDS.size()) invalid();
            Map<String, Integer> positions = new LinkedHashMap<>();
            for (int i = 0; i < fields.size(); i++) {
                String field = fields.get(i).asString();
                if (!FIELDS.contains(field) || positions.putIfAbsent(field, i) != null) invalid();
            }
            if (!positions.keySet().containsAll(FIELDS)) invalid();
            List<DailyQuote> quotes = new ArrayList<>();
            for (JsonNode item : items) {
                if (!item.isArray() || item.size() != fields.size()) invalid();
                String symbol = normalize(item.get(positions.get("ts_code")).asString());
                if (!requested.contains(symbol)) invalid();
                LocalDate date = parseDate(item.get(positions.get("trade_date")).asString());
                long open = cents(item.get(positions.get("open")));
                long high = cents(item.get(positions.get("high")));
                long low = cents(item.get(positions.get("low")));
                long close = cents(item.get(positions.get("close")));
                long preClose = cents(item.get(positions.get("pre_close")));
                BigDecimal pct = decimal(item.get(positions.get("pct_chg")));
                if (low > high || open < low || open > high || close < low || close > high) invalid();
                quotes.add(new DailyQuote(symbol, date, open, high, low, close, preClose, pct));
            }
            return quotes;
        } catch (MarketProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MarketProviderException("MARKET_UPSTREAM_INVALID", false);
        }
    }

    private static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!SYMBOL.matcher(value).matches()) throw new MarketProviderException("MARKET_UPSTREAM_INVALID", false);
        return value;
    }

    private static LocalDate parseDate(String raw) {
        try {
            LocalDate date = LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE);
            if (date.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) invalid();
            return date;
        } catch (DateTimeParseException exception) {
            throw new MarketProviderException("MARKET_UPSTREAM_INVALID", false);
        }
    }

    private static long cents(JsonNode value) {
        try {
            BigDecimal price = decimal(value);
            if (price.signum() <= 0) invalid();
            long cents = price.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            if (cents <= 0 || cents > MAX_CENTS) invalid();
            return cents;
        } catch (ArithmeticException exception) {
            throw new MarketProviderException("MARKET_UPSTREAM_INVALID", false);
        }
    }

    private static BigDecimal decimal(JsonNode value) {
        try {
            BigDecimal decimal = new BigDecimal(value.asString());
            if (decimal.scale() > 4 || decimal.abs().compareTo(new BigDecimal("99999.9999")) > 0) invalid();
            return decimal;
        } catch (NumberFormatException exception) {
            throw new MarketProviderException("MARKET_UPSTREAM_INVALID", false);
        }
    }

    private static void invalid() {
        throw new MarketProviderException("MARKET_UPSTREAM_INVALID", false);
    }
}
