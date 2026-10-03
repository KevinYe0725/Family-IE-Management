alter table investment_trades add unit_price decimal(25,6);
update investment_trades set unit_price=cast(price_cents as decimal(25,6))/100;
alter table investment_trades drop constraint ck_investment_trades_price;
alter table investment_trades add constraint ck_investment_trades_price check (
 price_cents>=0 and price_cents<=99999999999 and
 coalesce(unit_price,cast(price_cents as decimal(25,6))/100)>0 and
 coalesce(unit_price,cast(price_cents as decimal(25,6))/100)<=999999999.99);
