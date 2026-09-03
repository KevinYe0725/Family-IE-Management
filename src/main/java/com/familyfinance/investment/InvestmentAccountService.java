package com.familyfinance.investment;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InvestmentAccountService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final Sort SORT = Sort.by(Sort.Direction.DESC, "id");

    private final InvestmentAccountRepository accounts;
    private final CurrentMembership currentMembership;
    private final FamilyMutationAuthorization mutationAuthorization;
    private final Clock clock;

    public InvestmentAccountService(
            InvestmentAccountRepository accounts,
            CurrentMembership currentMembership,
            FamilyMutationAuthorization mutationAuthorization,
            Clock clock) {
        this.accounts = accounts;
        this.currentMembership = currentMembership;
        this.mutationAuthorization = mutationAuthorization;
        this.clock = clock;
    }

    public InvestmentAccountPage list(
            Authentication authentication, InvestmentAccountStatus status, int page, int size) {
        long householdId = currentMembership.require(authentication).householdId();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        var pageable = PageRequest.of(safePage, safeSize, SORT);
        var result = status == InvestmentAccountStatus.ARCHIVED
                ? accounts.findByHouseholdIdAndArchivedAtIsNotNull(householdId, pageable)
                : accounts.findByHouseholdIdAndArchivedAtIsNull(householdId, pageable);
        return new InvestmentAccountPage(
                result.getContent().stream().map(InvestmentAccountResponse::from).toList(),
                safePage, safeSize, result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    public InvestmentAccountResponse get(Authentication authentication, long id) {
        long householdId = currentMembership.require(authentication).householdId();
        return InvestmentAccountResponse.from(findOne(householdId, id));
    }

    @Transactional
    public InvestmentAccountResponse create(Authentication authentication, InvestmentAccountCreateRequest request) {
        var access = mutationAuthorization.requireAdmin(authentication);
        Map<String, String> fields = new LinkedHashMap<>();
        String name = required(request == null ? null : request.name(), 100, "name", "投资账户名称", fields);
        String broker = required(
                request == null ? null : request.brokerName(), 100, "brokerName", "券商名称", fields);
        String currency = request == null || request.currency() == null
                ? ""
                : request.currency().trim().toUpperCase(java.util.Locale.ROOT);
        if (!"CNY".equals(currency)) fields.put("currency", "投资账户币种只能是 CNY");
        throwIfInvalid(fields);
        ensureUnique(access.context().householdId(), name, null);
        try {
            return InvestmentAccountResponse.from(accounts.saveAndFlush(new InvestmentAccount(
                    access.household(), name, broker, access.membership().getUser())));
        } catch (DataIntegrityViolationException exception) {
            throw duplicate();
        }
    }

    @Transactional
    public InvestmentAccountResponse update(
            Authentication authentication, long id, InvestmentAccountPatchRequest request) {
        var access = mutationAuthorization.requireAdmin(authentication);
        InvestmentAccount account = findOne(access.context().householdId(), id);
        if (account.isArchived()) throw archived();
        Map<String, String> fields = new LinkedHashMap<>();
        if (request != null && request.currency() != null) fields.put("currency", "账户币种创建后不可修改");
        if (request != null && request.createdBy() != null) fields.put("createdBy", "创建者不可修改");
        if (request != null && request.archivedAt() != null) fields.put("archivedAt", "归档状态只能通过删除操作修改");
        String name = request == null || request.name() == null
                ? account.getName()
                : required(request.name(), 100, "name", "投资账户名称", fields);
        String broker = request == null || request.brokerName() == null
                ? account.getBrokerName()
                : required(request.brokerName(), 100, "brokerName", "券商名称", fields);
        throwIfInvalid(fields);
        ensureUnique(access.context().householdId(), name, id);
        try {
            account.update(name, broker);
            accounts.flush();
            return InvestmentAccountResponse.from(account);
        } catch (DataIntegrityViolationException exception) {
            throw duplicate();
        }
    }

    @Transactional
    public void archive(Authentication authentication, long id) {
        var access = mutationAuthorization.requireAdmin(authentication);
        InvestmentAccount account = findOne(access.context().householdId(), id);
        account.archive(clock.instant());
        accounts.flush();
    }

    InvestmentAccount findOne(long householdId, long id) {
        return accounts.findByIdAndHouseholdId(id, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("投资账户不存在"));
    }

    private void ensureUnique(long householdId, String name, Long id) {
        boolean exists = id == null
                ? accounts.existsByHouseholdIdAndName(householdId, name)
                : accounts.existsByHouseholdIdAndNameAndIdNot(householdId, name, id);
        if (exists) throw duplicate();
    }

    private static String required(
            String raw, int max, String field, String label, Map<String, String> fields) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) fields.put(field, label + "不能为空");
        else if (value.length() > max) fields.put(field, label + "长度不能超过 " + max + " 个字符");
        return value;
    }

    private static void throwIfInvalid(Map<String, String> fields) {
        if (!fields.isEmpty()) throw new RequestValidationException(fields);
    }

    private static ResourceConflictException duplicate() {
        return new ResourceConflictException("RESOURCE_CONFLICT", "同一家庭的投资账户名称不能重复");
    }

    private static ResourceConflictException archived() {
        return new ResourceConflictException("INVESTMENT_ACCOUNT_ARCHIVED", "投资账户已归档");
    }
}
