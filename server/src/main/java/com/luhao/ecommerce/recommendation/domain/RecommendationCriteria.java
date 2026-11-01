package com.luhao.ecommerce.recommendation.domain;

import com.luhao.ecommerce.shared.error.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;

/** Only these confirmed fields may participate in filtering. No SQL comes from a model. */
public record RecommendationCriteria(
        BigDecimal minPriceCny, BigDecimal maxPriceCny,
        Boolean minPriceInclusive, Boolean maxPriceInclusive,
        Boolean needsLaptop, LaptopSize laptop, Boolean machineWashRequired,
        List<String> unsupportedRequirements) {
    public record LaptopSize(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal thicknessCm) {}

    public RecommendationCriteria {
        minPriceInclusive = minPriceInclusive == null || minPriceInclusive;
        maxPriceInclusive = maxPriceInclusive == null || maxPriceInclusive;
        needsLaptop = Boolean.TRUE.equals(needsLaptop) || laptop != null;
        machineWashRequired = Boolean.TRUE.equals(machineWashRequired);
        unsupportedRequirements = unsupportedRequirements == null ? List.of() : List.copyOf(unsupportedRequirements);
    }

    public void validate() {
        if (!validPrice(minPriceCny) || !validPrice(maxPriceCny)) throw invalid("预算需为 0～1000000 元的金额，最多两位小数");
        if (minPriceCny != null && maxPriceCny != null &&
                (minPriceCny.compareTo(maxPriceCny) > 0 ||
                (minPriceCny.compareTo(maxPriceCny) == 0 && (!minPriceInclusive || !maxPriceInclusive))))
            throw invalid("预算上下限矛盾，请调整筛选条件");
        if (laptop != null && (!validDimension(laptop.lengthCm) || !validDimension(laptop.widthCm) || !validDimension(laptop.thicknessCm)))
            throw invalid("电脑尺寸需为大于 0 且不超过 200 cm 的数值，最多两位小数；尺寸不全时请留空整个尺寸组");
        if (unsupportedRequirements.size() > 10 || unsupportedRequirements.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 200))
            throw invalid("待核实要求最多 10 项，每项 1～200 字符");
    }

    private boolean validPrice(BigDecimal value) {
        return value == null || value.signum() >= 0 && value.compareTo(new BigDecimal("1000000")) <= 0 && value.stripTrailingZeros().scale() <= 2;
    }
    private boolean validDimension(BigDecimal value) {
        return value != null && value.signum() > 0 && value.compareTo(new BigDecimal("200")) <= 0 && value.stripTrailingZeros().scale() <= 2;
    }
    private ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CRITERIA", message); }
}
