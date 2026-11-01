package com.luhao.ecommerce.recommendation;

import com.luhao.ecommerce.recommendation.application.RecommendationService;
import com.luhao.ecommerce.recommendation.domain.RecommendationCriteria;
import com.luhao.ecommerce.recommendation.domain.RecommendationCriteria.LaptopSize;
import com.luhao.ecommerce.recommendation.infrastructure.RecommendationRepository;
import com.luhao.ecommerce.shared.error.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendationServiceTest {
    final RecommendationRepository repository = mock(RecommendationRepository.class);
    final RecommendationService service = new RecommendationService(repository);
    @Test void asksForMissingLaptopSizeWithoutFilteringOrGuessing() {
        var criteria = new RecommendationCriteria(null, new BigDecimal("200"), null, null, true, null, false, List.of());
        assertThat(service.recommend(criteria).get("status")).isEqualTo("NEED_INFO");
        verifyNoInteractions(repository);
    }
    @Test void doesNotSilentlyDropUnknownHardRequirements() {
        var criteria = new RecommendationCriteria(null, new BigDecimal("200"), null, null, false, null, false, List.of("防水等级IPX7"));
        var result = service.recommend(criteria);
        assertThat(result.get("status")).isEqualTo("INSUFFICIENT");
        assertThat((List<?>)result.get("candidates")).isEmpty();
        verifyNoInteractions(repository);
    }
    @Test void asksForAtLeastOneCondition() {
        var result = service.recommend(new RecommendationCriteria(null,null,null,null,false,null,false,List.of()));
        assertThat(result.get("status")).isEqualTo("NEED_INFO"); verifyNoInteractions(repository);
    }
    @Test void rejectsContradictoryBudgetsAndPartialDimensions() {
        for (var criteria : List.of(
                new RecommendationCriteria(new BigDecimal("200"),new BigDecimal("150"),null,null,false,null,false,List.of()),
                new RecommendationCriteria(new BigDecimal("150"),new BigDecimal("150"),false,true,false,null,false,List.of()),
                new RecommendationCriteria(null,null,null,null,true,new LaptopSize(new BigDecimal("35"),null,null),false,List.of()))) {
            assertThatThrownBy(() -> service.recommend(criteria)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo("INVALID_CRITERIA"));
        }
        verifyNoInteractions(repository);
    }
}
