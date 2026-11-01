package com.luhao.ecommerce.knowledge.domain;

public record TextChunk(int ordinal, String heading, int lineStart, int lineEnd, String content) {}
