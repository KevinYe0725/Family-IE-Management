import {dailyFxPair,estimateFxArrival} from './daily-fx';
const cny={currency:'CNY',cnyPerUnit:'1.000000000000',effectiveOn:'2026-09-09',state:'READY',source:'IDENTITY',batchId:null};
const usd={currency:'USD',cnyPerUnit:'7.000000000000',effectiveOn:'2026-09-08',state:'READY',source:'ECB',batchId:1};
const hkd={currency:'HKD',cnyPerUnit:'0.900000000000',effectiveOn:'2026-09-08',state:'READY',source:'ECB',batchId:1};
it('converts exact cents with the correct cross-rate direction and half-up rounding',()=>{
 expect(estimateFxArrival('7000','1','7')).toBe('1000.00');
 expect(estimateFxArrival('1','7','0.9')).toBe('7.78');
 expect(estimateFxArrival('0.01','1','2')).toBe('0.01');
 expect(estimateFxArrival('999999999.99','7','1')).toBeNull();
 expect(estimateFxArrival('1','0','1')).toBeNull();
});
it('allows identity plus a daily rate but rejects stale, future, or mixed publications',()=>{
 expect(dailyFxPair({asOf:'2026-09-09',rows:[cny,usd]},'CNY','USD','2026-09-09')?.effectiveOn).toBe('2026-09-08');
 expect(dailyFxPair({asOf:'2026-09-09',rows:[hkd,{...usd,batchId:2}]},'HKD','USD','2026-09-09')).toBeNull();
 expect(dailyFxPair({asOf:'2026-09-09',rows:[cny,{...usd,state:'STALE'}]},'CNY','USD','2026-09-09')).toBeNull();
 expect(dailyFxPair({asOf:'2026-09-09',rows:[cny,{...usd,effectiveOn:'2026-09-10'}]},'CNY','USD','2026-09-09')).toBeNull();
 expect(dailyFxPair({asOf:'2026-09-08',rows:[cny,usd]},'CNY','USD','2026-09-09')).toBeNull();
});
