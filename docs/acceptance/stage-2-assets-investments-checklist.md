# Stage 2 assets, investments, and market-data acceptance checklist

## Boundary

- [x] Real random-port HTTP acceptance uses cookie sessions and obtains a CSRF token before every write.
- [x] A household owner registers, creates a `PROPERTY` asset, and replaces its same-day manual valuation.
- [x] The owner creates a CNY investment account, resolves a local A-share security, and records a `BUY` trade.
- [x] A test-only in-process `MarketQuoteProvider` supplies one daily close; no Tushare endpoint, token, or test-only web route is used.
- [x] Refresh returns a validated Tushare-source close; portfolio verifies quantity, average-cost basis, market value, P&L, and price provenance.
- [x] The application is fully stopped and restarted against the same file H2 database; asset, valuation, account, security, trade, quote, and derived portfolio identities/values remain available.
- [x] A second process starts with `TUSHARE_TOKEN` explicitly blank. It remains healthy, refresh reports `MARKET_DISABLED`, and a manual same-day price replaces the effective quote and portfolio source without external I/O.

## Reproducible automated gate

```bash
./mvnw -q -Dtest=StageTwoAssetInvestmentSmokeTest test
```

The test deliberately leaves `app.scheduling.enabled=false`; scheduling has its own focused coverage. It is an API/runtime acceptance gate, not a browser-UI acceptance claim and not evidence of a live Tushare account.

## Manual demonstration notes

1. Start the application through the documented local launcher and log in as an owner or administrator.
2. Use the asset, investment-account, security, trade, market-refresh, manual-price, and portfolio APIs listed in the README. All writes first require `GET /api/csrf` and `X-XSRF-TOKEN`.
3. Leave `TUSHARE_TOKEN` unset to demonstrate the manual-only mode. A refresh must return `MARKET_DISABLED`; it must not prevent asset or investment work.
4. If a user chooses to enable Tushare, they supply `TUSHARE_TOKEN` only in their own process environment. Do not put it in source, tests, logs, documentation examples, or the H2 database.
