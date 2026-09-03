package com.familyfinance.market;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.investment.InvestmentTrade;
import com.familyfinance.investment.InvestmentTradeRepository;
import com.familyfinance.investment.InvestmentTradeType;
import com.familyfinance.investment.Security;
import com.familyfinance.investment.SecurityRepository;
import com.familyfinance.shared.ResourceConflictException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class QuoteRefreshService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final MarketQuoteProvider provider;
    private final InvestmentTradeRepository trades;
    private final SecurityRepository securities;
    private final MarketPriceSnapshotRepository snapshots;
    private final ManualPriceOverrideRepository overrides;
    private final CurrentMembership currentMembership;
    private final FamilyMutationAuthorization authorization;
    private final Clock clock;
    private final MarketSleeper sleeper;
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<MarketRefreshResponse>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Instant> lastManualRefresh = new ConcurrentHashMap<>();

    public QuoteRefreshService(MarketQuoteProvider provider, InvestmentTradeRepository trades,
            SecurityRepository securities, MarketPriceSnapshotRepository snapshots,
            ManualPriceOverrideRepository overrides, CurrentMembership currentMembership,
            FamilyMutationAuthorization authorization, Clock clock, MarketSleeper sleeper) {
        this.provider = provider; this.trades = trades; this.securities = securities; this.snapshots = snapshots;
        this.overrides = overrides; this.currentMembership = currentMembership; this.authorization = authorization;
        this.clock = clock; this.sleeper = sleeper;
    }

    @Transactional
    public MarketRefreshResponse refresh(Authentication authentication) {
        var access = authorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        CompletableFuture<MarketRefreshResponse> ownFlight = new CompletableFuture<>();
        CompletableFuture<MarketRefreshResponse> existing = inFlight.putIfAbsent(householdId, ownFlight);
        if (existing != null) return existing.join();
        ReentrantLock lock = locks.computeIfAbsent(householdId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            Instant now = clock.instant();
            Instant previous = lastManualRefresh.put(householdId, now);
            if (previous != null && previous.plus(Duration.ofMinutes(1)).isAfter(now)) {
                throw new ResourceConflictException("MARKET_REFRESH_RATE_LIMITED", "行情刷新过于频繁，请稍后再试");
            }
            List<Security> held = heldSecurities(householdId);
            MarketRefreshResponse response;
            if (!provider.available()) response = new MarketRefreshResponse("DISABLED", 0, "MARKET_DISABLED", prices(householdId, held));
            else if (held.isEmpty()) response = new MarketRefreshResponse("READY", 0, null, List.of());
            else {
            LocalDate today = today();
            if (hasToday(held, today)) response = new MarketRefreshResponse("READY", 0, null, prices(householdId, held));
            else {
                List<DailyQuote> result = fetchWithRetry(symbols(held));
                int saved = saveQuotes(result);
                response = new MarketRefreshResponse("READY", saved, null, prices(householdId, held));
            }
            }
            ownFlight.complete(response);
            return response;
        } catch (MarketProviderException exception) {
            MarketRefreshResponse response = new MarketRefreshResponse(
                    "ERROR", 0, exception.code(), prices(householdId, heldSecurities(householdId)));
            ownFlight.complete(response);
            return response;
        } catch (RuntimeException exception) {
            ownFlight.completeExceptionally(exception);
            throw exception;
        } finally {
            lock.unlock();
            inFlight.remove(householdId, ownFlight);
        }
    }

    public List<MarketPriceResponse> list(Authentication authentication) {
        long householdId = currentMembership.require(authentication).householdId();
        return prices(householdId, heldSecurities(householdId));
    }

    private List<DailyQuote> fetchWithRetry(Set<String> symbols) {
        for (int attempt = 0; ; attempt++) {
            try { return provider.fetchDaily(symbols); }
            catch (MarketProviderException exception) {
                if (!exception.retryable() || attempt == 2) throw exception;
                sleeper.sleep(Duration.ofMillis(50L * (attempt + 1)));
            }
        }
    }

    @Transactional
    int saveQuotes(List<DailyQuote> quotes) {
        int saved = 0;
        Instant now = clock.instant();
        Map<String, Security> byCode = new HashMap<>();
        for (Security security : securities.findAll()) byCode.put(security.getTsCode(), security);
        for (DailyQuote quote : quotes) {
            Security security = byCode.get(quote.symbol());
            if (security == null || !security.isActive()) continue;
            if (snapshots.findBySecurityIdAndTradeDate(security.getId(), quote.tradeDate()).isPresent()) continue;
            try { snapshots.saveAndFlush(new MarketPriceSnapshot(security, quote, now)); saved++; }
            catch (DataIntegrityViolationException ignored) { }
        }
        return saved;
    }

    private List<Security> heldSecurities(long householdId) {
        Map<String, BigDecimal> quantity = new HashMap<>();
        Map<String, Long> ids = new HashMap<>();
        for (InvestmentTrade trade : trades.findActiveAccountTradesByHouseholdId(householdId)) {
            String key = trade.getAccount().getId() + ":" + trade.getSecurity().getId();
            ids.put(key, trade.getSecurity().getId());
            if (trade.getType() == InvestmentTradeType.BUY) quantity.merge(key, trade.getQuantity(), BigDecimal::add);
            if (trade.getType() == InvestmentTradeType.SELL) quantity.merge(key, trade.getQuantity().negate(), BigDecimal::add);
        }
        Set<Long> securityIds = new HashSet<>();
        for (var entry : quantity.entrySet()) if (entry.getValue().signum() > 0) securityIds.add(ids.get(entry.getKey()));
        return securities.findAllById(securityIds).stream().filter(Security::isActive)
                .sorted(Comparator.comparing(Security::getTsCode)).toList();
    }

    private boolean hasToday(List<Security> held, LocalDate today) {
        if (held.isEmpty()) return true;
        Set<Long> ids = held.stream().map(Security::getId).collect(java.util.stream.Collectors.toSet());
        return snapshots.findBySecurityIdInAndTradeDate(ids, today).stream().map(snapshot -> snapshot.getSecurity().getId())
                .collect(java.util.stream.Collectors.toSet()).containsAll(ids);
    }

    private List<MarketPriceResponse> prices(long householdId, Collection<Security> held) {
        LocalDate today = today();
        List<MarketPriceResponse> result = new ArrayList<>();
        for (Security security : held) {
            var manual = overrides.findFirstByHouseholdIdAndSecurityIdAndEffectiveOnLessThanEqualOrderByEffectiveOnDescIdDesc(
                    householdId, security.getId(), today);
            if (manual.isPresent()) result.add(MarketPriceResponse.manual(security, manual.get(), manual.get().getEffectiveOn().isBefore(today)));
            else {
                var quote = snapshots.findFirstBySecurityIdOrderByTradeDateDescFetchedAtDescIdDesc(security.getId());
                result.add(quote.map(value -> MarketPriceResponse.tushare(security, value, value.getTradeDate().isBefore(today)))
                        .orElseGet(() -> MarketPriceResponse.noQuote(security, "NO_QUOTE")));
            }
        }
        return result;
    }

    private LocalDate today() { return LocalDate.now(clock.withZone(SHANGHAI)); }
    private static Set<String> symbols(List<Security> securities) {
        return securities.stream().map(Security::getTsCode).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
