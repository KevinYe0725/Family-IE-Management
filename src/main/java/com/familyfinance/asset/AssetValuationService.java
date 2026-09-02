package com.familyfinance.asset;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.shared.ResourceConflictException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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
public class AssetValuationService {

    private static final Sort HISTORY_SORT = Sort.by(
            Sort.Order.desc("valuedOn"), Sort.Order.desc("fetchedAt"), Sort.Order.desc("id"));
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final AssetRepository assets;
    private final AssetValuationRepository valuations;
    private final AssetService assetService;
    private final CurrentMembership currentMembership;
    private final FamilyMutationAuthorization mutationAuthorization;
    private final Clock clock;

    public AssetValuationService(
            AssetRepository assets,
            AssetValuationRepository valuations,
            AssetService assetService,
            CurrentMembership currentMembership,
            FamilyMutationAuthorization mutationAuthorization,
            Clock clock) {
        this.assets = assets;
        this.valuations = valuations;
        this.assetService = assetService;
        this.currentMembership = currentMembership;
        this.mutationAuthorization = mutationAuthorization;
        this.clock = clock;
    }

    public AssetValuationPage list(Authentication authentication, long assetId, int page, int size) {
        long householdId = currentMembership.require(authentication).householdId();
        assetService.findOne(householdId, assetId);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(AssetService.MAX_PAGE_SIZE, Math.max(1, size));
        var result = valuations.findByHouseholdIdAndAssetId(
                householdId, assetId, PageRequest.of(safePage, safeSize, HISTORY_SORT));
        return new AssetValuationPage(
                result.getContent().stream().map(AssetValuationResponse::from).toList(),
                safePage,
                safeSize,
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext());
    }

    @Transactional
    public AssetValuationResponse create(
            Authentication authentication, long assetId, AssetValuationRequest request) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        Asset asset = assetService.findOne(householdId, assetId);
        if (asset.isArchived()) {
            throw new ResourceConflictException("ASSET_ARCHIVED", "资产已归档");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        LocalDate valuedOn = request == null ? null : request.valuedOn();
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        if (valuedOn == null) fields.put("valuedOn", "估值日期不能为空");
        else if (valuedOn.isAfter(today)) fields.put("valuedOn", "估值日期不能晚于今天");
        Long value = AssetService.parseRequiredMoney(request == null ? null : request.value(), "value", fields);
        String note = normalizeNote(request == null ? null : request.note(), fields);
        if (!fields.isEmpty()) throw new AssetValidationException(fields);

        AssetValuation valuation = valuations.findByAssetIdAndValuedOnAndSource(
                        assetId, valuedOn, AssetValuationSource.MANUAL)
                .map(existing -> {
                    if (!valuedOn.equals(today)) {
                        throw new ResourceConflictException(
                                "VALUATION_IMMUTABLE", "历史估值不可改写");
                    }
                    existing.replaceManual(value, note, clock.instant());
                    return existing;
                })
                .orElseGet(() -> new AssetValuation(
                        access.household(), asset, valuedOn, value, AssetValuationSource.MANUAL,
                        note, access.membership().getUser(), clock.instant()));
        try {
            valuation = valuations.saveAndFlush(valuation);
        } catch (DataIntegrityViolationException exception) {
            throw new ResourceConflictException(
                    "VALUATION_CONFLICT", "同一日期和来源的估值已发生并发更新，请重试");
        }

        AssetValuation latest = valuations.findFirstByAssetIdOrderByValuedOnDescFetchedAtDescIdDesc(assetId)
                .orElseThrow();
        if (latest.getId().equals(valuation.getId())) {
            asset.updateCurrentValue(valuation.getValueCents());
            try {
                assets.flush();
            } catch (DataIntegrityViolationException exception) {
                throw new ResourceConflictException(
                        "VALUATION_PROJECTION_CONFLICT", "资产当前价值更新失败，请重试");
            }
        }
        return AssetValuationResponse.from(valuation);
    }

    private static String normalizeNote(String raw, Map<String, String> fields) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) fields.put("note", "估值备注不能为空字符串");
        else if (value.length() > 500) fields.put("note", "估值备注长度不能超过 500 个字符");
        return value;
    }
}
