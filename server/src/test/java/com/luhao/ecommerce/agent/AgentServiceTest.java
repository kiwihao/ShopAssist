package com.luhao.ecommerce.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luhao.ecommerce.agent.application.AgentService;
import com.luhao.ecommerce.model.application.ToolCallingClient;
import com.luhao.ecommerce.product.application.ProductService;
import com.luhao.ecommerce.qa.application.QaService;
import com.luhao.ecommerce.recommendation.application.RequirementService;
import com.luhao.ecommerce.shared.error.ApiException;
import com.luhao.ecommerce.ticket.application.TicketService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentServiceTest {
    final ObjectMapper json=new ObjectMapper();
    final ToolCallingClient model=mock(ToolCallingClient.class);
    final QaService qa=mock(QaService.class);
    final RequirementService requirements=mock(RequirementService.class);
    final ProductService products=mock(ProductService.class);
    final TicketService tickets=mock(TicketService.class);
    final AgentService service=new AgentService(model,qa,requirements,products,json,tickets);
    com.fasterxml.jackson.databind.JsonNode call(String id,String name,String arguments) {
        return json.valueToTree(Map.of("role","assistant","tool_calls",List.of(Map.of("id",id,"type","function","function",Map.of("name",name,"arguments",arguments)))));
    }
    com.fasterxml.jackson.databind.JsonNode done() { return json.valueToTree(Map.of("role","assistant","content","{\"done\":true}")); }
    @Test void executesKnowledgeAndReturnsResultToModelWithoutUsingUnverifiedFinalText() {
        when(qa.answer("BP-A","尺寸")).thenReturn(Map.of("status","ANSWER","answer","36 cm","citations",List.of()));
        when(model.next(anyList(),anyList())).thenReturn(call("c1","query_product_knowledge","{}"))
                .thenAnswer(invocation->{
                    List<Map<String,Object>> messages=invocation.getArgument(0);
                    assertThat(messages.getLast().get("role")).isEqualTo("tool");
                    assertThat(messages.getLast().get("tool_call_id")).isEqualTo("c1");
                    assertThat(messages.getLast().get("content").toString()).contains("36 cm");
                    return json.valueToTree(Map.of("content","编造承重100kg"));
                });
        var result=service.run("BP-A","尺寸");
        assertThat(result.get("message")).isEqualTo("36 cm");
        assertThat(result.get("toolCalls")).isEqualTo(1);
        verify(qa,times(1)).answer("BP-A","尺寸");
    }
    @Test void recommendationsStopAtHumanReviewAndPreserveExtractedCriteria() {
        when(requirements.understand("预算150推荐背包")).thenReturn(Map.of("criteria",Map.of("maxPriceCny",150)));
        when(model.next(anyList(),anyList())).thenReturn(call("c1","prepare_recommendation","{}"),done());
        var result=service.run("BP-A","预算150推荐背包");
        assertThat(result.get("status")).isEqualTo("NEED_CONFIRMATION");
        assertThat(result.get("criteria")).isEqualTo(Map.of("maxPriceCny",150));
        verifyNoInteractions(qa);
    }
    @Test void invalidArgumentsCanBeRepairedWithoutExecutingTheFirstCall() {
        when(qa.answer("BP-A","尺寸")).thenReturn(Map.of("status","ANSWER","answer","36"));
        when(model.next(anyList(),anyList())).thenReturn(call("bad","query_product_knowledge","{\"productId\":\"BP-B\"}"),call("ok","query_product_knowledge","{}"),done());
        var result=service.run("BP-A","尺寸");
        assertThat(result.get("toolCalls")).isEqualTo(2);
        verify(qa,times(1)).answer("BP-A","尺寸");
    }
    @Test void endlesslyInvalidCallsStopAtTheLimit() {
        when(model.next(anyList(),anyList())).thenReturn(call("a","unknown","{}"),call("b","unknown","{}"),call("c","unknown","{}"),call("d","unknown","{}"));
        var result=service.run("BP-A","尺寸");
        assertThat(result.get("status")).isEqualTo("LIMIT_REACHED");
        assertThat(result.get("toolCalls")).isEqualTo(3);
        verifyNoInteractions(qa,requirements);
    }
    @Test void unrepairedToolErrorsAreNotMisreportedAsMissingUserInformation() {
        when(model.next(anyList(),anyList())).thenReturn(call("a","unknown","{}"),done());
        assertThat(service.run("BP-A","尺寸").get("status")).isEqualTo("TOOL_ERROR");
        verifyNoInteractions(qa,requirements);
    }
    @Test void failedBusinessToolIsReportedAndNeverRetriedAutomatically() {
        when(qa.answer("BP-A","尺寸")).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY,"CHAT_HTTP_503","upstream failed"));
        when(model.next(anyList(),anyList())).thenReturn(call("a","query_product_knowledge","{}"),done());
        var result=service.run("BP-A","尺寸");
        assertThat(result.get("status")).isEqualTo("TOOL_ERROR");
        assertThat(result.get("errorCode")).isEqualTo("CHAT_HTTP_503");
        verify(qa,times(1)).answer("BP-A","尺寸");
    }
    @Test void missingOrMismatchedProductAsksInsteadOfSearchingWrongProduct() {
        when(model.next(anyList(),anyList())).thenReturn(call("a","query_product_knowledge","{}"),done());
        assertThat(service.run(null,"尺寸").get("status")).isEqualTo("NEED_INFO");
        when(products.list()).thenReturn(List.of(Map.of("product_id","BP-B","name","简装轻便包")));
        when(model.next(anyList(),anyList())).thenReturn(call("b","query_product_knowledge","{}"),done());
        assertThat(service.run("BP-A","BP-B的尺寸").get("status")).isEqualTo("NEED_INFO");
        verifyNoInteractions(qa);
    }
    @Test void duplicateToolCallDoesNotRepeatBusinessExecution() {
        when(qa.answer("BP-A","尺寸")).thenReturn(Map.of("status","ANSWER","answer","36"));
        when(model.next(anyList(),anyList())).thenReturn(call("a","query_product_knowledge","{}"),call("b","query_product_knowledge","{}"),done());
        assertThat(service.run("BP-A","尺寸").get("message")).isEqualTo("36");
        verify(qa,times(1)).answer("BP-A","尺寸");
    }
    @Test void ticketToolOnlyPreparesDraftAndNeverCallsConfirmEvenWhenUserSaysConfirmed() {
        when(tickets.prepare("BP-A","拉链坏了，请建工单，我已确认")).thenReturn(Map.of("draft_id","draft-1","status","DRAFT","confirmationToken","private-confirmation-capability"));
        when(model.next(anyList(),anyList())).thenReturn(call("draft","prepare_support_ticket","{}"))
                .thenAnswer(invocation->{
                    List<Map<String,Object>> messages=invocation.getArgument(0);
                    assertThat(messages.getLast().get("content").toString()).doesNotContain("confirmationToken","private-confirmation-capability");
                    return done();
                });
        var result=service.run("BP-A","拉链坏了，请建工单，我已确认");
        assertThat(result.get("status")).isEqualTo("NEED_CONFIRMATION");
        assertThat(result).containsKey("ticketDraft");
        verify(tickets).prepare("BP-A","拉链坏了，请建工单，我已确认");
        verifyNoMoreInteractions(tickets);
    }
}
