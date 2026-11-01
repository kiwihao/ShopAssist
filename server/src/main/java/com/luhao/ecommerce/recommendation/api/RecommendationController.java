package com.luhao.ecommerce.recommendation.api;

import com.luhao.ecommerce.recommendation.application.RecommendationService;
import com.luhao.ecommerce.recommendation.application.RequirementService;
import com.luhao.ecommerce.recommendation.domain.RecommendationCriteria;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {
    private final RecommendationService recommendations;
    private final RequirementService requirements;
    public RecommendationController(RecommendationService recommendations, RequirementService requirements) {
        this.recommendations = recommendations; this.requirements = requirements;
    }
    @PostMapping("/understand") public Map<String, Object> understand(@RequestBody Requirement request) {
        return requirements.understand(request.message());
    }
    @PostMapping public Map<String, Object> recommend(@RequestBody RecommendationCriteria criteria) {
        return recommendations.recommend(criteria);
    }
    public record Requirement(String message) {}
}
