package com.luhao.ecommerce.ticket.application;

import com.luhao.ecommerce.product.application.ProductService;
import com.luhao.ecommerce.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService {
    private final JdbcTemplate jdbc;
    private final ProductService products;
    public TicketService(JdbcTemplate jdbc,ProductService products) { this.jdbc=jdbc; this.products=products; }

    @Transactional
    public Map<String,Object> prepare(String productId,String summary) {
        products.get(productId); String text=validateSummary(summary);
        UUID id=UUID.randomUUID(); String token=token();
        jdbc.update("INSERT INTO ticket_drafts(draft_id,product_id,summary,token_hash,expires_at) VALUES (?,?,?,?,now()+interval '30 minutes')",id,productId,text,hash(token));
        return draftResponse(id,token);
    }
    @Transactional
    public Map<String,Object> edit(UUID id,Integer version,String confirmationToken,String summary) {
        var draft=lock(id); authorize(draft,version,confirmationToken); editable(draft);
        String text=validateSummary(summary),token=token();
        jdbc.update("UPDATE ticket_drafts SET summary=?,version=version+1,token_hash=?,expires_at=now()+interval '30 minutes' WHERE draft_id=?",text,hash(token),id);
        return draftResponse(id,token);
    }
    @Transactional
    public Map<String,Object> cancel(UUID id,Integer version,String confirmationToken) {
        var draft=lock(id); authorize(draft,version,confirmationToken); editable(draft);
        jdbc.update("UPDATE ticket_drafts SET status='CANCELLED' WHERE draft_id=?",id);
        return Map.of("draftId",id,"status","CANCELLED");
    }
    @Transactional
    public Map<String,Object> confirm(UUID id,Integer version,String confirmationToken,Boolean confirmed,String key) {
        if(!Boolean.TRUE.equals(confirmed)) throw fail(400,"CONFIRMATION_REQUIRED","请明确确认当前草稿后再创建工单");
        if(key==null || !key.matches("[A-Za-z0-9_-]{8,100}")) throw fail(400,"INVALID_IDEMPOTENCY_KEY","请提供8～100位字母、数字、下划线或短横线的幂等键");
        var draft=lock(id); authorize(draft,version,confirmationToken);
        // Check the key before replaying a completed draft: one key cannot identify two payloads.
        var byKey=jdbc.queryForList("SELECT draft_id,draft_version,token_hash FROM consultation_tickets WHERE idempotency_key=?",key);
        if(!byKey.isEmpty() && (!byKey.getFirst().get("draft_id").equals(id)
                || !byKey.getFirst().get("draft_version").equals(version)
                || !byKey.getFirst().get("token_hash").equals(hash(confirmationToken))))
            throw fail(409,"IDEMPOTENCY_CONFLICT","该幂等键已用于其他确认内容，请勿复用");
        var existing=tickets(id);
        if(!existing.isEmpty()) return ticketResponse(existing.getFirst(),true);
        editable(draft);
        UUID ticketId=UUID.randomUUID(); String number="SA-"+ticketId.toString().replace("-","").toUpperCase(Locale.ROOT);
        int inserted=jdbc.update("""
                INSERT INTO consultation_tickets(ticket_id,ticket_number,draft_id,product_id,summary,draft_version,token_hash,idempotency_key)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """,ticketId,number,id,draft.get("product_id"),draft.get("summary"),version,hash(confirmationToken),key);
        if(inserted!=1) throw fail(409,"IDEMPOTENCY_CONFLICT","幂等键或草稿已用于其他提交，请核对后重试");
        jdbc.update("UPDATE ticket_drafts SET status='CREATED' WHERE draft_id=?",id);
        return ticketResponse(tickets(id).getFirst(),false);
    }
    private Map<String,Object> lock(UUID id) {
        var rows=jdbc.queryForList("SELECT *,expires_at <= now() AS expired FROM ticket_drafts WHERE draft_id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw fail(404,"DRAFT_NOT_FOUND","草稿不存在");
        return rows.getFirst();
    }
    private void authorize(Map<String,Object> draft,Integer version,String token) {
        if(version==null || !draft.get("version").equals(version)) throw fail(409,"DRAFT_VERSION_CONFLICT","草稿已更新，请核对最新内容后确认");
        if(token==null || token.length()>200 || !MessageDigest.isEqual(draft.get("token_hash").toString().getBytes(StandardCharsets.UTF_8),hash(token).getBytes(StandardCharsets.UTF_8)))
            throw fail(403,"INVALID_CONFIRMATION_TOKEN","确认令牌不正确，请重新生成草稿");
    }
    private void editable(Map<String,Object> draft) {
        if(!draft.get("status").equals("DRAFT")) throw fail(409,"DRAFT_NOT_EDITABLE","草稿已创建工单或已取消，不能再次修改或提交");
        if(Boolean.TRUE.equals(draft.get("expired"))) throw fail(410,"DRAFT_EXPIRED","草稿已过期，请重新生成后核对");
    }
    private Map<String,Object> draftResponse(UUID id,String token) {
        var row=jdbc.queryForMap("SELECT draft_id,product_id,summary,version,status,expires_at FROM ticket_drafts WHERE draft_id=?",id);
        var result=new LinkedHashMap<>(row); result.put("confirmationToken",token);
        result.put("notice","仅登记咨询问题，不代表退款、退货或赔付已获批准。请核对当前内容后再确认创建。");
        result.put("expires_at",row.get("expires_at").toString());
        return result;
    }
    private List<Map<String,Object>> tickets(UUID id) {
        return jdbc.queryForList("SELECT ticket_id,ticket_number,draft_id,product_id,summary,status,created_at FROM consultation_tickets WHERE draft_id=?",id);
    }
    private Map<String,Object> ticketResponse(Map<String,Object> ticket,boolean replayed) {
        var result=new LinkedHashMap<>(ticket); result.put("created_at",ticket.get("created_at").toString()); result.put("replayed",replayed); return result;
    }
    private String validateSummary(String summary) {
        if(summary==null || summary.isBlank() || summary.strip().codePointCount(0,summary.strip().length())>1000)
            throw fail(400,"INVALID_TICKET_SUMMARY","请填写1～1000字符的咨询问题");
        return summary.strip();
    }
    private String token() { return UUID.randomUUID().toString()+UUID.randomUUID(); }
    private String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private ApiException fail(int status,String code,String message) { return new ApiException(HttpStatus.valueOf(status),code,message); }
}
