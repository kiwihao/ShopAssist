package com.luhao.ecommerce.model.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

public interface ToolCallingClient {
    JsonNode next(List<Map<String,Object>> messages, List<Map<String,Object>> tools);
}
