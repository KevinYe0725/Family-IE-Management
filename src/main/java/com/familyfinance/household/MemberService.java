package com.familyfinance.household;

import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import com.familyfinance.transaction.FinancialTransactionRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MemberService {

    private final HouseholdRepository householdRepository;
    private final FamilyMemberRepository memberRepository;
    private final FinancialTransactionRepository transactionRepository;

    public MemberService(
            HouseholdRepository householdRepository,
            FamilyMemberRepository memberRepository,
            FinancialTransactionRepository transactionRepository) {
        this.householdRepository = householdRepository;
        this.memberRepository = memberRepository;
        this.transactionRepository = transactionRepository;
    }

    public List<MemberResponse> list(long householdId) {
        return memberRepository.findByHouseholdIdOrderById(householdId).stream()
                .map(MemberResponse::from)
                .toList();
    }

    @Transactional
    public MemberResponse create(long householdId, MemberRequest request) {
        Household household = householdRepository.findById(householdId)
                .orElseThrow(() -> new ResourceNotFoundException("家庭不存在"));
        FamilyMember member = memberRepository.save(new FamilyMember(
                household,
                normalizeName(request.name()),
                normalizeRoleLabel(request.roleLabel()),
                Instant.now()));
        return MemberResponse.from(member);
    }

    @Transactional
    public MemberResponse update(long householdId, long memberId, MemberRequest request) {
        FamilyMember member = memberRepository.findByIdAndHouseholdId(memberId, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("成员不存在"));
        member.updateProfile(normalizeName(request.name()), normalizeRoleLabel(request.roleLabel()));
        return MemberResponse.from(member);
    }

    @Transactional
    public void delete(long householdId, long memberId) {
        FamilyMember member = memberRepository.findByIdAndHouseholdId(memberId, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("成员不存在"));
        if (transactionRepository.existsByHouseholdIdAndMemberId(householdId, memberId)) {
            throw new ResourceConflictException("RESOURCE_IN_USE", "该成员已被收支记录使用，无法删除");
        }
        try {
            memberRepository.delete(member);
            memberRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new ResourceConflictException("RESOURCE_IN_USE", "该成员已被收支记录使用，无法删除");
        }
    }

    private String normalizeName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw validationError("name", "成员姓名不能为空");
        }
        if (name.length() > 30) {
            throw validationError("name", "成员姓名长度不能超过 30 个字符");
        }
        return name;
    }

    private String normalizeRoleLabel(String rawRoleLabel) {
        String roleLabel = rawRoleLabel == null ? "" : rawRoleLabel.trim();
        if (roleLabel.length() > 30) {
            throw validationError("roleLabel", "成员身份长度不能超过 30 个字符");
        }
        return roleLabel;
    }

    private RequestValidationException validationError(String field, String message) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(field, message);
        return new RequestValidationException(fields);
    }
}
