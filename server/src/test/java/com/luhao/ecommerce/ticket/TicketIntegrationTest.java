package com.luhao.ecommerce.ticket;

import com.luhao.ecommerce.ticket.application.TicketService;
import com.luhao.ecommerce.shared.error.ApiException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/ecommerce_rag_ticket_test?connectTimeout=3&socketTimeout=3")
@EnabledIfEnvironmentVariable(named="DEMO_INTEGRATION_TESTS",matches="true")
class TicketIntegrationTest {
    @Autowired TicketService tickets;
    @Autowired JdbcTemplate jdbc;
    Map<String,Object> draft() { return tickets.prepare("BP-A","演示咨询：拉链无法拉合，请协助核实"); }
    UUID id(Map<String,Object> d) { return (UUID)d.get("draft_id"); }
    String token(Map<String,Object> d) { return d.get("confirmationToken").toString(); }
    int version(Map<String,Object> d) { return (Integer)d.get("version"); }
    Map<String,Object> confirm(Map<String,Object> d,String key) { return tickets.confirm(id(d),version(d),token(d),true,key); }
    int count(Map<String,Object> d) { return jdbc.queryForObject("SELECT count(*) FROM consultation_tickets WHERE draft_id=?",Integer.class,id(d)); }
    void fails(String code,Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo(code)); }
    @Test void preparingDoesNotCreateTicketAndRequiresExplicitConfirmation() {
        var d=draft(); assertThat(count(d)).isZero();
        fails("CONFIRMATION_REQUIRED",()->tickets.confirm(id(d),1,token(d),false,UUID.randomUUID().toString()));
        assertThat(count(d)).isZero();
        assertThat(confirm(d,UUID.randomUUID().toString()).get("status")).isEqualTo("OPEN");
    }
    @Test void editRotatesConfirmationAndOldContentCannotBeCommitted() {
        var old=draft(); var updated=tickets.edit(id(old),1,token(old),"更新后的咨询内容");
        assertThat(version(updated)).isEqualTo(2); assertThat(token(updated)).isNotEqualTo(token(old));
        fails("DRAFT_VERSION_CONFLICT",()->confirm(old,UUID.randomUUID().toString()));
        fails("INVALID_CONFIRMATION_TOKEN",()->tickets.confirm(id(old),2,token(old),true,UUID.randomUUID().toString()));
        assertThat(count(old)).isZero();
        assertThat(confirm(updated,UUID.randomUUID().toString()).get("summary")).isEqualTo("更新后的咨询内容");
        fails("DRAFT_NOT_EDITABLE",()->tickets.edit(id(updated),2,token(updated),"不允许创建后改写"));
    }
    @Test void retriesAndDifferentKeysCannotCreateMoreThanOneTicketForSameDraft() {
        var d=draft(); String key=UUID.randomUUID().toString(); var first=confirm(d,key); var replay=confirm(d,key);
        assertThat(replay.get("ticket_id")).isEqualTo(first.get("ticket_id")); assertThat(replay.get("replayed")).isEqualTo(true);
        assertThat(confirm(d,UUID.randomUUID().toString()).get("ticket_id")).isEqualTo(first.get("ticket_id"));
        assertThat(count(d)).isEqualTo(1);
    }
    @Test void keyCannotBeReusedForAnotherDraft() {
        var first=draft(); var second=draft(); String key=UUID.randomUUID().toString(); confirm(first,key);
        fails("IDEMPOTENCY_CONFLICT",()->confirm(second,key)); assertThat(count(second)).isZero();
    }
    @Test void sixConcurrentConfirmationsCreateExactlyOneRow() throws Exception {
        var d=draft(); String key=UUID.randomUUID().toString();
        try(var executor=Executors.newFixedThreadPool(6)) {
            var start=new CountDownLatch(1); List<Future<Object>> futures=new ArrayList<>();
            for(int i=0;i<6;i++) futures.add(executor.submit(()->{start.await(); return confirm(d,key).get("ticket_id");}));
            start.countDown(); var ids=new HashSet<>();
            for(var future:futures) ids.add(future.get(15,TimeUnit.SECONDS));
            assertThat(ids).hasSize(1); assertThat(count(d)).isEqualTo(1);
        }
    }
    @Test void expiredCancelledAndIncorrectTokensCannotCreateTickets() {
        var expired=draft(); jdbc.update("UPDATE ticket_drafts SET expires_at=now()-interval '1 minute' WHERE draft_id=?",id(expired));
        fails("DRAFT_EXPIRED",()->confirm(expired,UUID.randomUUID().toString())); assertThat(count(expired)).isZero();
        var cancelled=draft(); tickets.cancel(id(cancelled),1,token(cancelled));
        fails("DRAFT_NOT_EDITABLE",()->confirm(cancelled,UUID.randomUUID().toString())); assertThat(count(cancelled)).isZero();
        var invalid=draft(); fails("INVALID_CONFIRMATION_TOKEN",()->tickets.confirm(id(invalid),1,"wrong-token",true,UUID.randomUUID().toString())); assertThat(count(invalid)).isZero();
    }
    @Test void lostResponseCanBeRecoveredEvenAfterDraftExpiry() {
        var d=draft(); String key=UUID.randomUUID().toString(); var created=confirm(d,key);
        jdbc.update("UPDATE ticket_drafts SET expires_at=now()-interval '1 minute' WHERE draft_id=?",id(d));
        assertThat(confirm(d,key).get("ticket_id")).isEqualTo(created.get("ticket_id"));
    }
}
