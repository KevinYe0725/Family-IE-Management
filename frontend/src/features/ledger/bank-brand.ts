const banks: Array<[RegExp, string]> = [
  [/汇丰|hsbc/i, 'hsbc'],
  [/工商银行|工行|\bicbc\b/i, 'icbc'],
  [/建设银行|建行|\bccb\b/i, 'ccb'],
  [/中国银行|中行|bank\s*of\s*china|\bboc\b/i, 'boc'],
  [/农业银行|农行|agricultural\s*bank|abchina|\babc\b/i, 'abchina'],
  [/交通银行|交行|bank\s*of\s*communications|bankcomm|\bbocom\b/i, 'bankcomm'],
  [/招商银行|招行|china\s*merchants\s*bank|cmbchina|^cmb$/i, 'cmbchina'],
  [/花旗|citibank|^citi$/i, 'citibank'],
  [/中信银行|citic/i, 'citicbank'],
  [/平安银行|ping\s*an/i, 'pingan'],
  [/邮政储蓄|邮储|postal\s*savings|psbc/i, 'psbc'],
  [/浦发|浦东发展|shanghai\s*pudong|spdb/i, 'spdb'],
];
export function bankLogo(name?: string | null): string | null {
  const key = banks.find(([pattern]) => pattern.test(name?.trim() ?? ''))?.[1];
  return key ? `/bank-logos/${key}.svg` : null;
}
