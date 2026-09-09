package com.familyfinance.asset;
import java.time.Instant;
public record AssetSaleResult(long saleId,long assetId,AssetSalePreview preview,Instant recordedAt){}
