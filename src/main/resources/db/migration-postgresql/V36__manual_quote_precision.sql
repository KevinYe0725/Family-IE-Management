alter table manual_price_overrides add unit_price decimal(25,6);
update manual_price_overrides set unit_price=cast(price_cents as decimal(25,6))/100;
alter table manual_price_overrides drop constraint ck_manual_price_overrides_price;
alter table manual_price_overrides add constraint ck_manual_price_overrides_price check (
 price_cents>=0 and price_cents<=99999999999 and coalesce(unit_price,cast(price_cents as decimal(25,6))/100)>0
 and coalesce(unit_price,cast(price_cents as decimal(25,6))/100)<=999999999.99);
