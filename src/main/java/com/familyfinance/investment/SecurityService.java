package com.familyfinance.investment;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SecurityService {

    private static final int MAX_PAGE_SIZE = 20;
    private static final Pattern A_SHARE = Pattern.compile("^[0-9]{6}[.](SH|SZ|BJ)$");
    private static final Sort SORT = Sort.by(
            Sort.Order.asc("tsCode"), Sort.Order.asc("name"), Sort.Order.asc("id"));

    private final SecurityRepository securities;
    private final CurrentMembership currentMembership;
    private final FamilyMutationAuthorization mutationAuthorization;
    private final JdbcTemplate jdbc;

    public SecurityService(
            SecurityRepository securities,
            CurrentMembership currentMembership,
            FamilyMutationAuthorization mutationAuthorization,
            JdbcTemplate jdbc) {
        this.securities = securities;
        this.currentMembership = currentMembership;
        this.mutationAuthorization = mutationAuthorization;
        this.jdbc = jdbc;
    }

    public SecurityPage search(Authentication authentication, String rawQuery, int page, int size) {
        currentMembership.require(authentication);
        String query = rawQuery == null ? "" : rawQuery.trim().toUpperCase(Locale.ROOT);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        var result = securities.search(query, PageRequest.of(safePage, safeSize, SORT));
        return new SecurityPage(
                result.getContent().stream().map(SecurityResponse::from).toList(), safePage, safeSize,
                result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    @Transactional
    public SecurityResponse resolve(Authentication authentication, SecurityResolveRequest request) {
        mutationAuthorization.requireAdmin(authentication);
        Map<String, String> fields = new LinkedHashMap<>();
        Security security = resolve(
                request == null ? null : request.tsCode(), request == null ? null : request.name(), fields);
        throwIfInvalid(fields);
        return SecurityResponse.from(security);
    }

    Security resolve(String rawCode, String rawName, Map<String, String> fields) {
        String code = normalizeCode(rawCode, fields);
        String name = normalizeName(rawName, fields);
        if (!fields.isEmpty()) return null;
        Security existing = securities.findByTsCodeAndActiveTrue(code).orElse(null);
        if (existing != null) return existing;
        String market = code.substring(7);
        try {
            jdbc.update("""
                    insert into securities (market,ts_code,name,security_type,active)
                    values (?,?,?,'STOCK',true)
                    """, market, code, name);
        } catch (DataIntegrityViolationException ignoredDuplicate) {
            // A concurrent household may have registered the same shared reference row.
        }
        return securities.findByTsCodeAndActiveTrue(code)
                .orElseThrow(() -> new IllegalStateException("证券代码创建后无法读取"));
    }

    Security findActive(long id, Map<String, String> fields) {
        return securities.findByIdAndActiveTrue(id).orElseGet(() -> {
            fields.put("securityId", "证券不存在或已停用");
            return null;
        });
    }

    private static String normalizeCode(String raw, Map<String, String> fields) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!A_SHARE.matcher(value).matches()) {
            fields.put("tsCode", "股票代码必须是六位数字加 .SH、.SZ 或 .BJ");
        }
        return value;
    }

    private static String normalizeName(String raw, Map<String, String> fields) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) fields.put("name", "证券名称不能为空");
        else if (value.length() > 100) fields.put("name", "证券名称长度不能超过 100 个字符");
        return value;
    }

    static void throwIfInvalid(Map<String, String> fields) {
        if (!fields.isEmpty()) throw new InvestmentValidationException(fields);
    }
}
