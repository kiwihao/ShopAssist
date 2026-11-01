package com.luhao.ecommerce.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luhao.ecommerce.model.application.ChatClient;
import com.luhao.ecommerce.recommendation.application.RequirementService;
import com.luhao.ecommerce.recommendation.domain.RecommendationCriteria;
import com.luhao.ecommerce.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

class RequirementServiceTest {
    final ChatClient chat = mock(ChatClient.class);
    final RequirementService service = new RequirementService(chat, new ObjectMapper());
    final String valid = """
            {"minPriceCny":null,"maxPriceCny":150,"minPriceInclusive":true,"maxPriceInclusive":false,
             "needsLaptop":true,"laptop":null,"machineWashRequired":false,"unsupportedRequirements":[]}
            """;
    @Test void preservesStrictBudgetAndMissingLaptopDimensions() {
        when(chat.complete(anyString(), anyString())).thenReturn(valid);
        var result = (RecommendationCriteria)service.understand("不到150，能放14寸电脑").get("criteria");
        assertThat(result.maxPriceCny()).isEqualByComparingTo("150");
        assertThat(result.maxPriceInclusive()).isFalse();
        assertThat(result.needsLaptop()).isTrue();
        assertThat(result.laptop()).isNull();
    }
    @Test void rejectsHallucinatedSchemaOrWrongTypesOrInvalidAmounts() {
        for (String answer : new String[]{valid.replace("150", "-1"), valid.replace("150", "\"150\""), valid.replace("\"needsLaptop\":true", "\"needsLaptop\":\"true\""), valid.replace("\"laptop\":null", "\"laptop\":{\"lengthCm\":35}"), valid.replace("\"unsupportedRequirements\":[]", "\"unsupportedRequirements\":[],\"sql\":\"SELECT *\"")}) {
            when(chat.complete(anyString(), anyString())).thenReturn(answer);
            assertThatThrownBy(() -> service.understand("需求")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("REQUIREMENT_INVALID"));
        }
    }
    @Test void rejectsEmptyRequirementBeforeNetworkCall() {
        assertThatThrownBy(() -> service.understand("  ")).isInstanceOf(ApiException.class);
        verifyNoInteractions(chat);
    }
    @Test void propagatesUpstreamFailureInsteadOfInventingRequirements() {
        var failure = new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,"CHAT_HTTP_503","test failure");
        when(chat.complete(anyString(), anyString())).thenThrow(failure);
        assertThatThrownBy(() -> service.understand("推荐背包")).isSameAs(failure);
    }
}
