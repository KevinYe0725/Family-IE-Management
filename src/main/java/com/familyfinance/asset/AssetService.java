package com.familyfinance.asset;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.loan.LoanRepository;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
@Transactional(readOnly = true)
public class AssetService {

    static final int MAX_PAGE_SIZE = 50;
    static final long MAX_CENTS = 999_999_999_999L;
    private static final Pattern MONEY = Pattern.compile("^(\\d+)(?:\\.(\\d{1,2}))?$");
    private static final Pattern AREA = Pattern.compile("^\\d+(?:\\.\\d{1,2})?$");
    private static final BigInteger HUNDRED = BigInteger.valueOf(100);
    private static final BigInteger MAX_CENTS_INTEGER = BigInteger.valueOf(MAX_CENTS);
    private static final BigDecimal MAX_AREA = new BigDecimal("9999999999.99");
    private static final Sort ASSET_SORT = Sort.by(Sort.Direction.DESC, "id");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final AssetRepository assets;
    private final AssetValuationRepository valuations;
    private final FamilyMemberRepository members;
    private final CurrentMembership currentMembership;
    private final FamilyMutationAuthorization mutationAuthorization;
    private final LoanRepository loans;
    private final Clock clock;
    private final com.familyfinance.accounting.AccountingRequests requests;
    private final AssetAccountingService accounting;
    private final jakarta.persistence.EntityManager entities;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public AssetService(
            AssetRepository assets,
            AssetValuationRepository valuations,
            FamilyMemberRepository members,
            CurrentMembership currentMembership,
            FamilyMutationAuthorization mutationAuthorization,
            LoanRepository loans,
            Clock clock,com.familyfinance.accounting.AccountingRequests requests,
            AssetAccountingService accounting,jakarta.persistence.EntityManager entities,org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.assets = assets;
        this.valuations = valuations;
        this.members = members;
        this.currentMembership = currentMembership;
        this.mutationAuthorization = mutationAuthorization;
        this.loans = loans;
        this.clock = clock;
        this.requests=requests;this.accounting=accounting;this.entities=entities;this.jdbc=jdbc;
    }

    public AssetPage list(
            Authentication authentication, AssetType type, AssetStatus status, int page, int size) {
        long householdId = currentMembership.require(authentication).householdId();
        int safePage = Math.max(0, page);
        int safeSize = safeSize(size);
        AssetStatus safeStatus = status == null ? AssetStatus.ACTIVE : status;
        PageRequest pageable = PageRequest.of(safePage, safeSize, ASSET_SORT);
        Page<Asset> result = type == null
                ? assets.findByHouseholdIdAndStatus(householdId, safeStatus, pageable)
                : assets.findByHouseholdIdAndTypeAndStatus(householdId, type, safeStatus, pageable);
        return new AssetPage(
                result.getContent().stream().map(this::response).toList(),
                safePage,
                safeSize,
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext());
    }

    public AssetResponse get(Authentication authentication, long assetId) {
        long householdId = currentMembership.require(authentication).householdId();
        return response(findOne(householdId, assetId));
    }

    @Transactional
    public AssetResponse create(Authentication authentication, AssetCreateRequest request) {
        return create(authentication,request,com.familyfinance.accounting.AccountingRequests.key(null));
    }
    @Transactional
    public AssetResponse create(Authentication authentication,AssetCreateRequest request,String key) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        String digest=requests.digest("ASSET_CREATE",access.context().userId(),request);
        Long replay=requests.replay(householdId,key,digest);
        if(replay!=null)return response(findCurrent(householdId,replay));
        Map<String, String> fields = new LinkedHashMap<>();
        String name = normalizeRequired(request == null ? null : request.name(), 100, "name", "资产名称", fields);
        AssetType type = request == null ? null : request.type();
        if (type == null) fields.put("type", "资产类型不能为空");
        FamilyMember owner = resolveOwner(householdId, request == null ? null : request.ownerMemberId(), fields);
        LocalDate acquiredOn = request == null ? null : request.acquiredOn();
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        if (acquiredOn != null && acquiredOn.isAfter(today)) {
            fields.put("acquiredOn", "取得日期不能晚于今天");
        }
        Long purchaseValue = parseOptionalMoney(
                request == null ? null : request.purchaseValue(), "purchaseValue", fields);
        if (purchaseValue != null && acquiredOn == null) {
            fields.put("acquiredOn", "填写购买价值时必须填写取得日期");
        }
        Long currentValue = parseRequiredMoney(
                request == null ? null : request.currentValue(), "currentValue", fields);
        ParsedProperty property = validateProperty(
                type, request == null ? null : request.property(), request == null ? null : request.vehicle(), true, fields);
        ParsedVehicle vehicle = validateVehicle(
                type, request == null ? null : request.vehicle(), request == null ? null : request.property(), true, fields);
        AssetAccountingMode mode=request==null?null:request.accountingMode();
        if(mode==null)fields.put("accountingMode","请选择期初资产或真实购买");
        if(mode==AssetAccountingMode.FINANCED_PURCHASE)fields.put("accountingMode","请从新建贷款选择本次贷款购买物");
        if(mode==AssetAccountingMode.PURCHASE&&(purchaseValue==null||request.fundingAccountId()==null))
            fields.put("fundingAccountId","真实购买必须填写购买价格和资金账户");
        if(mode==AssetAccountingMode.OPENING&&request.fundingAccountId()!=null)
            fields.put("fundingAccountId","期初资产不使用资金账户");
        throwIfInvalid(fields);
        LocalDate accountingOn=accounting.day(request.accountingOn(),"accountingOn");

        Asset asset = new Asset(
                access.household(), name, type, owner, acquiredOn, purchaseValue, currentValue,
                access.membership().getUser());
        long initial=mode==AssetAccountingMode.PURCHASE?purchaseValue:currentValue;
        asset.initialize(mode,accountingOn,initial,request.fundingAccountId());
        if (property != null) {
            asset.attachProperty(new PropertyAsset(
                    asset, householdId, property.address(), property.areaSqm(), property.usageType()));
        }
        if (vehicle != null) {
            asset.attachVehicle(new VehicleAsset(
                    asset, householdId, vehicle.brandModel(), vehicle.plateHint(), vehicle.purchaseYear()));
        }
        try {
            asset = assets.saveAndFlush(asset);
            AssetValuation valuation=valuations.saveAndFlush(new AssetValuation(
                    access.household(), asset, accountingOn, currentValue, AssetValuationSource.MANUAL,
                    null, access.membership().getUser(), clock.instant()));
            accounting.originate(asset,access.context().userId(),key);
            accounting.revalue(asset,valuation.getId(),accountingOn,initial,currentValue,access.context().userId(),"asset-value:"+valuation.getId());
            requests.record(householdId,key,digest,asset.getId());
            return response(asset);
        } catch (DataIntegrityViolationException exception) {
            throw new ResourceConflictException("RESOURCE_CONFLICT", "资产无法保存，请刷新后重试");
        }
    }

    @Transactional
    public AssetResponse update(Authentication authentication, long assetId, AssetPatchRequest request) {
        return update(authentication,assetId,request,com.familyfinance.accounting.AccountingRequests.key(null));
    }
    @Transactional
    public AssetResponse update(Authentication authentication,long assetId,AssetPatchRequest request,String key) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        String digest=requests.digest("ASSET_UPDATE:"+assetId,access.context().userId(),request);
        Long replay=requests.replay(householdId,key,digest);
        Asset asset = findCurrent(householdId, assetId);
        if(replay!=null)return response(asset);
        if (asset.isArchived()) throw archived();
        rejectImmutablePatch(request);
        Map<String, String> fields = new LinkedHashMap<>();
        String name = request == null || request.name() == null
                ? asset.getName()
                : normalizeRequired(request.name(), 100, "name", "资产名称", fields);
        FamilyMember owner = request == null || request.ownerMemberId() == null
                ? asset.getOwnerMember()
                : resolvePatchOwner(householdId, request.ownerMemberId(), fields);
        ParsedProperty property = validateProperty(
                asset.getType(), request == null ? null : request.property(),
                request == null ? null : request.vehicle(), false, fields);
        ParsedVehicle vehicle = validateVehicle(
                asset.getType(), request == null ? null : request.vehicle(),
                request == null ? null : request.property(), false, fields);
        throwIfInvalid(fields);

        asset.updateProfile(name, owner);
        if (property != null) {
            if(asset.getProperty()==null)asset.attachProperty(new PropertyAsset(asset,householdId,property.address(),property.areaSqm(),property.usageType()));
            else asset.getProperty().update(property.address(), property.areaSqm(), property.usageType());
        }
        if (vehicle != null) {
            if(asset.getVehicle()==null)asset.attachVehicle(new VehicleAsset(asset,householdId,vehicle.brandModel(),vehicle.plateHint(),vehicle.purchaseYear()));
            else asset.getVehicle().update(vehicle.brandModel(), vehicle.plateHint(), vehicle.purchaseYear());
        }
        assets.flush();
        requests.record(householdId,key,digest,assetId);
        return response(asset);
    }

    @Transactional
    public void archive(Authentication authentication, long assetId) {
        archive(authentication,assetId,com.familyfinance.accounting.AccountingRequests.key(null));
    }
    @Transactional
    public void archive(Authentication authentication,long assetId,String key) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        String digest=requests.digest("ASSET_ARCHIVE:"+assetId,access.context().userId(),null);
        if(requests.replay(householdId,key,digest)!=null)return;
        Asset asset = findCurrent(householdId, assetId);
        accounting.requireBalance(asset);
        if(asset.getCurrentValueCents()!=0)throw new ResourceConflictException("ASSET_VALUE_NOT_ZERO","请先记录资产处置收入或损失，再归档");
        if (hasLoanReference(householdId, assetId)) {
            throw new ResourceConflictException("RESOURCE_IN_USE", "资产仍被贷款引用，无法归档");
        }
        asset.archive(clock.instant());
        assets.flush();
        requests.record(householdId,key,digest,assetId);
    }

    @Transactional
    public AssetResponse dispose(Authentication authentication,long assetId,AssetDisposalRequest request,String key) {
        var access=mutationAuthorization.requireAdmin(authentication);
        long h=access.context().householdId();
        String digest=requests.digest("ASSET_DISPOSE:"+assetId,access.context().userId(),request);
        Long replay=requests.replay(h,key,digest);
        Asset asset=findCurrent(h,assetId);
        if(replay!=null)return response(asset);
        if(asset.isArchived())throw archived();
        Map<String,String> fields=new LinkedHashMap<>();
        Long proceeds=parseRequiredMoney(request==null?null:request.proceeds(),"proceeds",fields);
        throwIfInvalid(fields);
        LocalDate day=accounting.day(request.disposedOn(),"disposedOn");
        accounting.dispose(asset,day,proceeds,request.cashAccountId(),access.context().userId(),key);
        asset.dispose(day,proceeds,request.cashAccountId(),access.context().userId(),clock.instant());
        assets.flush();
        requests.record(h,key,digest,assetId);
        return response(asset);
    }

    @Transactional
    public void cancel(Authentication authentication, long assetId) {
        cancel(authentication,assetId,com.familyfinance.accounting.AccountingRequests.key(null));
    }
    @Transactional
    public void cancel(Authentication authentication,long assetId,String key) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        String digest=requests.digest("ASSET_CANCEL:"+assetId,access.context().userId(),assetId);
        if(requests.replay(householdId,key,digest)!=null)return;
        Asset asset = findCurrent(householdId, assetId);
        if (asset.isArchived()) throw archived();
        if (asset.getPurchaseLoanId() != null)
            throw new ResourceConflictException("ASSET_FINANCED_CANCEL_VIA_LOAN","该资产为贷款购买物，请从贷款计划取消对应贷款（将一并撤销资产）");
        if (asset.getAccountingMode() == null)
            throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","旧资产尚未确认账务期初，不能取消");
        accounting.requireBalance(asset);
        if (asset.getDisposedOn() != null)
            throw new ResourceConflictException("ASSET_DISPOSED","资产已处置，不能取消");
        if (hasLoanReference(householdId, assetId))
            throw new ResourceConflictException("RESOURCE_IN_USE","资产仍被贷款引用，无法取消；请先取消关联贷款");
        if (valuations.countByHouseholdIdAndAssetId(householdId, assetId) > 1)
            throw new ResourceConflictException("ASSET_HAS_VALUATIONS","资产已有估值记录，暂不支持取消；请先删除后续估值后再取消");
        Long initialValuationId = valuations.findFirstByAssetIdOrderByValuedOnDescFetchedAtDescIdDesc(assetId)
                .map(AssetValuation::getId).orElse(null);
        // 红字冲销取得凭证与创建时的估值差异（如现金购入，现金原路退回；期初资产则回冲期初权益）。
        accounting.cancel(asset,initialValuationId,access.context().userId(),key);
        jdbc.update("delete from asset_valuations where household_id=? and asset_id=?",householdId,assetId);
        jdbc.update("delete from property_assets where household_id=? and asset_id=?",householdId,assetId);
        jdbc.update("delete from vehicle_assets where household_id=? and asset_id=?",householdId,assetId);
        jdbc.update("delete from assets where household_id=? and id=?",householdId,assetId);
        requests.record(householdId,key,digest,assetId);
    }

    private AssetResponse response(Asset asset) {
        boolean current=!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        return AssetResponse.from(asset,accounting.disposalBookGain(asset,current));
    }

    Asset findCurrent(long h,long id) {
        var asset=assets.findCurrent(id,h).orElseThrow(()->new ResourceNotFoundException("资产不存在"));
        entities.detach(asset);
        asset=assets.findCurrent(id,h).orElseThrow();
        // A root lock (including FOR UPDATE OF root on joins) does not make subtype
        // reads current under MySQL RR. Lock each child before deciding insert vs update.
        if(asset.getProperty()!=null)entities.detach(asset.getProperty());
        if(asset.getVehicle()!=null)entities.detach(asset.getVehicle());
        var property=entities.createQuery("select p from PropertyAsset p where p.asset.id=:id and p.householdId=:h",PropertyAsset.class)
                .setParameter("id",id).setParameter("h",h).setFlushMode(jakarta.persistence.FlushModeType.COMMIT).setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE).getResultList();
        if(!property.isEmpty())asset.attachProperty(property.get(0));
        var vehicle=entities.createQuery("select v from VehicleAsset v where v.asset.id=:id and v.householdId=:h",VehicleAsset.class)
                .setParameter("id",id).setParameter("h",h).setFlushMode(jakarta.persistence.FlushModeType.COMMIT).setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE).getResultList();
        if(!vehicle.isEmpty())asset.attachVehicle(vehicle.get(0));
        return asset;
    }

    Asset findOne(long householdId, long assetId) {
        return assets.findByIdAndHouseholdId(assetId, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("资产不存在"));
    }

    private boolean hasLoanReference(long householdId, long assetId) {
        return loans.existsByHouseholdIdAndLinkedAsset_Id(householdId, assetId);
    }

    private FamilyMember resolveOwner(long householdId, Long ownerMemberId, Map<String, String> fields) {
        if (ownerMemberId == null) return null;
        return members.findByIdAndHouseholdId(ownerMemberId, householdId).orElseGet(() -> {
            fields.put("ownerMemberId", "资产所有者必须属于当前家庭");
            return null;
        });
    }

    private FamilyMember resolvePatchOwner(long householdId, JsonNode node, Map<String, String> fields) {
        if (node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0) {
            fields.put("ownerMemberId", "资产所有者编号格式不正确");
            return null;
        }
        return resolveOwner(householdId, node.longValue(), fields);
    }

    private static ParsedProperty validateProperty(
            AssetType type,
            PropertyAssetRequest request,
            VehicleAssetRequest vehicle,
            boolean required,
            Map<String, String> fields) {
        if (type == AssetType.PROPERTY) {
            if (vehicle != null) fields.put("vehicle", "房产不能包含车辆明细");
            if (request == null) {
                if (required) fields.put("property", "房产明细不能为空");
                return null;
            }
            String address = normalizeRequired(
                    request.address(), 255, "property.address", "房产地址", fields);
            BigDecimal area = parseArea(request.areaSqm(), fields);
            String usage = normalizeRequired(
                    request.usageType(), 32, "property.usageType", "房产用途", fields);
            return new ParsedProperty(address, area, usage);
        }
        if (request != null) fields.put("property", "只有房产可以包含房产明细");
        return null;
    }

    private static ParsedVehicle validateVehicle(
            AssetType type,
            VehicleAssetRequest request,
            PropertyAssetRequest property,
            boolean required,
            Map<String, String> fields) {
        if (type == AssetType.VEHICLE) {
            if (property != null) fields.put("property", "车辆不能包含房产明细");
            if (request == null) {
                if (required) fields.put("vehicle", "车辆明细不能为空");
                return null;
            }
            String model = normalizeRequired(
                    request.brandModel(), 120, "vehicle.brandModel", "车辆品牌型号", fields);
            String plate = normalizeOptional(request.plateHint(), 32, "vehicle.plateHint", "车牌提示", fields);
            Integer year = request.purchaseYear();
            if (year != null && (year < 1886 || year > 9999)) {
                fields.put("vehicle.purchaseYear", "购车年份必须在 1886 到 9999 之间");
            }
            return new ParsedVehicle(model, plate, year);
        }
        if (request != null) fields.put("vehicle", "只有车辆可以包含车辆明细");
        return null;
    }

    private static BigDecimal parseArea(String raw, Map<String, String> fields) {
        String value = raw == null ? "" : raw.trim();
        if (!AREA.matcher(value).matches()) {
            fields.put("property.areaSqm", "房产面积格式必须是最多两位小数的正数");
            return null;
        }
        try {
            BigDecimal area = new BigDecimal(value);
            if (area.scale() > 2 || area.signum() <= 0 || area.compareTo(MAX_AREA) > 0) {
                fields.put("property.areaSqm", "房产面积必须大于 0、最多两位小数且不超过 9,999,999,999.99");
                return null;
            }
            return area;
        } catch (NumberFormatException exception) {
            fields.put("property.areaSqm", "房产面积格式不正确");
            return null;
        }
    }

    static Long parseRequiredMoney(String raw, String field, Map<String, String> fields) {
        if (raw == null || raw.isBlank()) {
            fields.put(field, "金额不能为空");
            return null;
        }
        return parseMoney(raw, field, fields);
    }

    static Long parseOptionalMoney(String raw, String field, Map<String, String> fields) {
        if (raw == null) return null;
        if (raw.isBlank()) {
            fields.put(field, "金额格式必须是最多两位小数的非负数字");
            return null;
        }
        return parseMoney(raw, field, fields);
    }

    private static Long parseMoney(String raw, String field, Map<String, String> fields) {
        Matcher matcher = MONEY.matcher(raw.trim());
        if (!matcher.matches()) {
            fields.put(field, "金额格式必须是最多两位小数的非负数字");
            return null;
        }
        BigInteger cents = new BigInteger(matcher.group(1)).multiply(HUNDRED);
        if (matcher.group(2) != null) {
            String fraction = matcher.group(2);
            cents = cents.add(BigInteger.valueOf(Long.parseLong(fraction.length() == 1 ? fraction + "0" : fraction)));
        }
        if (cents.compareTo(MAX_CENTS_INTEGER) > 0) {
            fields.put(field, "金额不能超过 9,999,999,999.99");
            return null;
        }
        return cents.longValueExact();
    }

    private static String normalizeRequired(
            String raw, int max, String field, String label, Map<String, String> fields) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) fields.put(field, label + "不能为空");
        else if (value.length() > max) fields.put(field, label + "长度不能超过 " + max + " 个字符");
        return value;
    }

    private static String normalizeOptional(
            String raw, int max, String field, String label, Map<String, String> fields) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) fields.put(field, label + "不能为空字符串");
        else if (value.length() > max) fields.put(field, label + "长度不能超过 " + max + " 个字符");
        return value;
    }

    private static void rejectImmutablePatch(AssetPatchRequest request) {
        if (request == null) return;
        Map<String, String> fields = new LinkedHashMap<>();
        if (request.type() != null) fields.put("type", "资产类型创建后不可修改");
        if (request.acquiredOn() != null) fields.put("acquiredOn", "取得日期创建后不可修改");
        if (request.purchaseValue() != null) fields.put("purchaseValue", "购买价值创建后不可修改");
        if (request.currentValue() != null) fields.put("currentValue", "当前价值只能通过估值更新");
        if (request.createdBy() != null) fields.put("createdBy", "创建者不可修改");
        if (request.status() != null) fields.put("status", "资产状态只能通过归档操作修改");
        if (!fields.isEmpty()) throw new RequestValidationException(fields);
    }

    private static void throwIfInvalid(Map<String, String> fields) {
        if (!fields.isEmpty()) throw new AssetValidationException(fields);
    }

    private static int safeSize(int size) {
        return Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    }

    private static ResourceConflictException archived() {
        return new ResourceConflictException("ASSET_ARCHIVED", "资产已归档");
    }

    private record ParsedProperty(String address, BigDecimal areaSqm, String usageType) {
    }

    private record ParsedVehicle(String brandModel, String plateHint, Integer purchaseYear) {
    }
}
