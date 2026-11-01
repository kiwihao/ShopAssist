package com.luhao.ecommerce.recommendation.application;

import com.luhao.ecommerce.recommendation.domain.RecommendationCriteria;
import com.luhao.ecommerce.recommendation.infrastructure.RecommendationRepository;
import com.luhao.ecommerce.shared.error.ApiException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RecommendationService {
    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
    private final RecommendationRepository repository;
    public RecommendationService(RecommendationRepository repository) { this.repository = repository; }

    public Map<String, Object> recommend(RecommendationCriteria criteria) {
        if (criteria == null) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CRITERIA", "请提供筛选条件");
        criteria.validate();
        String requestId = UUID.randomUUID().toString();
        if (!criteria.unsupportedRequirements().isEmpty())
            return response(requestId, "INSUFFICIENT", "现有商品属性无法可靠核实以下要求，请补充资料或明确放弃这些条件：" + String.join("、", criteria.unsupportedRequirements()), criteria, List.of());
        if (criteria.needsLaptop() && criteria.laptop() == null)
            return response(requestId, "NEED_INFO", "请补充电脑（含保护壳）的外形长、宽、厚，单位 cm；屏幕英寸不能代替机身尺寸。", criteria, List.of());
        if (criteria.minPriceCny() == null && criteria.maxPriceCny() == null && criteria.laptop() == null && !criteria.machineWashRequired())
            return response(requestId, "NEED_INFO", "请先提供预算范围，或携带电脑的外形尺寸、是否必须机洗，至少明确一项筛选条件。", criteria, List.of());
        long start = System.nanoTime();
        var candidates = new ArrayList<Map<String, Object>>();
        for (var product : repository.filter(criteria)) {
            String productId = product.get("product_id").toString();
            var citations = new ArrayList<Map<String, Object>>();
            for (var chunk : repository.evidence(productId)) {
                var citation = new LinkedHashMap<>(chunk);
                citation.put("sourceId", "S" + (citations.size() + 1));
                citations.add(citation);
            }
            var reasons = new ArrayList<String>();
            reasons.add("演示价格 ¥" + product.get("price_cny") + (criteria.minPriceCny() == null && criteria.maxPriceCny() == null ? "（未设置预算限制）。" : "，符合已确认的预算条件。"));
            if (criteria.laptop() != null) reasons.add("电脑仓 " + product.get("laptop_height_cm") + " × " + product.get("laptop_width_cm") + " × " + product.get("laptop_depth_cm") + " cm，各参考尺寸不小于电脑外形尺寸（允许在平面内旋转）。");
            if (criteria.machineWashRequired()) reasons.add("商品属性标记为支持机洗，具体清洁限制请核对说明原文。");
            candidates.add(Map.of("product", product, "reasons", reasons, "citations", citations,
                    "evidenceStatus", citations.isEmpty() ? "MISSING" : "AVAILABLE"));
        }
        String status = candidates.isEmpty() ? "NO_MATCH" : "CANDIDATES";
        String message = candidates.isEmpty() ? "没有商品满足全部已确认条件。未自动放宽预算、尺寸或机洗要求，请自行调整后再查找。"
                : "找到 " + candidates.size() + " 款参数符合的候选商品，按价格从低到高展示。价格及筛选结论来自商品属性，原文资料供进一步核对。";
        log.info("recommendation requestId={} status={} candidates={} elapsedMs={}", requestId, status, candidates.size(), (System.nanoTime()-start)/1_000_000);
        return response(requestId, status, message, criteria, candidates);
    }

    private Map<String, Object> response(String requestId, String status, String message, RecommendationCriteria criteria, List<?> candidates) {
        return Map.of("requestId", requestId, "status", status, "message", message, "criteria", criteria,
                "candidates", candidates, "notice", "仅针对虚拟演示背包。电脑仓尺寸只做初步筛选，仓口、公差和装入余量未知，不能保证一定装得下；没有完成下单或库存校验。");
    }
}
