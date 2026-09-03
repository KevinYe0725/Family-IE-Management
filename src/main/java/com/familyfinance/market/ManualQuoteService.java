package com.familyfinance.market;

import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.investment.Security;
import com.familyfinance.investment.SecurityRepository;
import com.familyfinance.shared.Money;
import com.familyfinance.shared.ResourceNotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ManualQuoteService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final ManualPriceOverrideRepository overrides;
    private final SecurityRepository securities;
    private final FamilyMutationAuthorization authorization;
    private final Clock clock;

    public ManualQuoteService(ManualPriceOverrideRepository overrides, SecurityRepository securities,
            FamilyMutationAuthorization authorization, Clock clock) {
        this.overrides = overrides; this.securities = securities; this.authorization = authorization; this.clock = clock;
    }

    @Transactional
    public MarketPriceResponse set(Authentication authentication, long securityId, ManualPriceRequest request) {
        var access = authorization.requireAdmin(authentication);
        Security security = securities.findByIdAndActiveTrue(securityId)
                .orElseThrow(() -> new ResourceNotFoundException("证券不存在"));
        Map<String, String> fields = new LinkedHashMap<>();
        Long price = price(request == null ? null : request.price(), fields);
        LocalDate effectiveOn = request == null ? null : request.effectiveOn();
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        if (effectiveOn == null) fields.put("effectiveOn", "生效日期不能为空");
        else if (effectiveOn.isAfter(today)) fields.put("effectiveOn", "生效日期不能晚于今天");
        String note = note(request == null ? null : request.note(), fields);
        if (!fields.isEmpty()) throw new MarketValidationException(fields);
        ManualPriceOverride override = overrides.findByHouseholdIdAndSecurityIdAndEffectiveOn(
                        access.context().householdId(), securityId, effectiveOn)
                .map(existing -> { existing.replace(price, note); return existing; })
                .orElseGet(() -> new ManualPriceOverride(
                        access.household(), security, price, effectiveOn, note, access.membership().getUser()));
        override = overrides.saveAndFlush(override);
        return MarketPriceResponse.manual(security, override, effectiveOn.isBefore(today));
    }

    private static Long price(String raw, Map<String, String> fields) {
        try { return Money.parseCents(raw); }
        catch (IllegalArgumentException exception) { fields.put("price", exception.getMessage()); return null; }
    }

    private static String note(String raw, Map<String, String> fields) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) fields.put("note", "备注不能为空字符串");
        else if (value.length() > 500) fields.put("note", "备注长度不能超过 500 个字符");
        return value;
    }
}
