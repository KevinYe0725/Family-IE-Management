package com.familyfinance.asset;

import com.familyfinance.shared.ResourceNotFoundException;
import java.sql.*;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Confirmed receipts are immutable, including after later changes to a retained loan. */
@Repository
public class AssetSaleReceiptRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;
    public AssetSaleReceiptRepository(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    public AssetSaleResult append(long household,long actor,String key,AssetSaleDraft draft,AssetSalePreview preview,Instant now){
        var holder=new GeneratedKeyHolder();
        jdbc.update(c->{
            var statement=c.prepareStatement("insert into asset_sale_receipts(household_id,asset_id,actor_id,request_key,draft_json,preview_json,recorded_at) values(?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1,household);statement.setLong(2,preview.assetId());statement.setLong(3,actor);statement.setString(4,key);
            statement.setString(5,mapper.writeValueAsString(draft));statement.setString(6,mapper.writeValueAsString(preview));
            statement.setTimestamp(7,Timestamp.from(now));return statement;
        },holder);
        return byId(household,preview.assetId(),holder.getKey().longValue(),true);
    }
    public AssetSaleResult byId(long household,long asset,long id,boolean locked){
        var result=read(household,asset,id,locked);
        if(result==null)throw new ResourceNotFoundException("资产出售回执不存在");return result;
    }
    public AssetSaleResult read(long household,long asset,Long id,boolean locked){
        var rows=jdbc.query("select id,asset_id,preview_json,recorded_at from asset_sale_receipts where household_id=? and asset_id=?"
            +(id==null?"":" and id=?")+(locked?" for update":""),
            (r,n)->new AssetSaleResult(r.getLong(1),r.getLong(2),mapper.readValue(r.getString(3),AssetSalePreview.class),r.getTimestamp(4).toInstant()),
            id==null?new Object[]{household,asset}:new Object[]{household,asset,id});
        return rows.isEmpty()?null:rows.get(0);
    }
}
