package com.luhao.ecommerce.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luhao.ecommerce.model.application.ChatClient;
import com.luhao.ecommerce.qa.application.QaService;
import com.luhao.ecommerce.retrieval.application.RetrievalService;
import com.luhao.ecommerce.shared.error.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QaServiceTest {
    RetrievalService retrieval=mock(RetrievalService.class);
    ChatClient chat=mock(ChatClient.class);
    QaService qa=new QaService(retrieval,chat,new ObjectMapper());
    void evidence(List<?> matches) {
        when(retrieval.search("BP-A","尺寸",5)).thenReturn(Map.of("requestId","r1","productId","BP-A","matches",matches));
    }
    @Test void noEvidenceDoesNotInvokeChat() {
        evidence(List.of());assertThat(qa.answer("BP-A","尺寸").get("status")).isEqualTo("INSUFFICIENT");
        verifyNoInteractions(chat);
    }
    @Test void onlyReturnsCitedEvidenceFromCurrentSearch() {
        evidence(List.of(Map.of("chunk_id","c1","content","36 cm")));
        when(chat.complete(anyString(),anyString())).thenReturn("{\"status\":\"ANSWER\",\"answer\":\"36 cm [S1]\",\"citationIds\":[\"S1\"]}");
        assertThat((List<?>)qa.answer("BP-A","尺寸").get("citations")).hasSize(1);
    }
    @Test void rejectsInventedCitationOrMissingAnswerCitation() {
        evidence(List.of(Map.of("chunk_id","c1")));
        for(String ids:List.of("[\"S99\"]","[]")) {
            when(chat.complete(anyString(),anyString())).thenReturn("{\"status\":\"ANSWER\",\"answer\":\"结论\",\"citationIds\":"+ids+"}");
            assertThatThrownBy(()->qa.answer("BP-A","尺寸")).isInstanceOf(ApiException.class);
        }
    }
    @Test void modelFailureIsNotReportedAsMissingKnowledge() {
        evidence(List.of(Map.of("chunk_id","c1","content","36 cm")));
        var failure=new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CHAT_HTTP_503","上游不可用");
        when(chat.complete(anyString(),anyString())).thenThrow(failure);
        assertThatThrownBy(()->qa.answer("BP-A","尺寸")).isSameAs(failure);
    }
}
