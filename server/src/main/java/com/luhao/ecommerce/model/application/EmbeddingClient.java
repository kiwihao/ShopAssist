package com.luhao.ecommerce.model.application;

import java.util.List;

public interface EmbeddingClient {
    int DIMENSIONS = 1024;
    String MODEL = "embedding-3";
    default String model() { return MODEL; }
    void requireConfigured();
    String space();
    List<float[]> embed(List<String> texts);
}
