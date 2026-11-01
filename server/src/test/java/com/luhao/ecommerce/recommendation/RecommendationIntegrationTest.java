package com.luhao.ecommerce.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luhao.ecommerce.model.application.ChatClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real PostgreSQL filtering + MVC. No real model call; all test rows roll back. */
@SpringBootTest(properties="spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/ecommerce_rag_recommendation_test?connectTimeout=3&socketTimeout=3")
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named="DEMO_INTEGRATION_TESTS", matches="true")
class RecommendationIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean ChatClient chat;

    @Test void budgetBoundariesAndDimensionsFilterBeforeExplanation() throws Exception {
        check(Map.of("maxPriceCny",150),"CANDIDATES",1,"BP-B");
        check(Map.of("maxPriceCny",129,"maxPriceInclusive",true),"CANDIDATES",1,"BP-B");
        check(Map.of("maxPriceCny",129,"maxPriceInclusive",false),"NO_MATCH",0,null);
        check(Map.of("minPriceCny",150,"maxPriceCny",200),"CANDIDATES",1,"BP-A");
        check(Map.of("maxPriceCny",200,"laptop",Map.of("lengthCm",35,"widthCm",24,"thicknessCm",2)),"CANDIDATES",1,"BP-A");
        check(Map.of("maxPriceCny",200,"laptop",Map.of("lengthCm",24,"widthCm",35,"thicknessCm",2)),"CANDIDATES",1,"BP-A");
        check(Map.of("maxPriceCny",200,"laptop",Map.of("lengthCm",36,"widthCm",25,"thicknessCm",3)),"CANDIDATES",1,"BP-A");
        check(Map.of("maxPriceCny",150,"laptop",Map.of("lengthCm",35,"widthCm",24,"thicknessCm",2)),"NO_MATCH",0,null);
        verifyNoInteractions(chat);
    }
    @Test void inactiveAndUnknownWashabilityCannotSlipIntoMachineWashResults() throws Exception {
        jdbc.update("INSERT INTO products VALUES ('TEST-OFF','下架包','背包',1,40,30,5,'INACTIVE',true), ('TEST-UNKNOWN','未知包','背包',1,40,30,5,'ACTIVE',null)");
        check(Map.of("maxPriceCny",200,"machineWashRequired",true),"NO_MATCH",0,null);
        check(Map.of("maxPriceCny",100),"CANDIDATES",1,"TEST-UNKNOWN");
    }
    @Test void invalidInputReturns400AndMissingConditionsAreExplicit() throws Exception {
        http.perform(post("/api/recommendations").contentType("application/json").content("{\"minPriceCny\":200,\"maxPriceCny\":150}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CRITERIA"));
        http.perform(post("/api/recommendations").contentType("application/json").content("{bad json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        check(Map.of("maxPriceCny",200,"needsLaptop",true),"NEED_INFO",0,null);
        check(Map.of("maxPriceCny",200,"unsupportedRequirements",new String[]{"必须防水"}),"INSUFFICIENT",0,null);
        check(Map.of(),"NEED_INFO",0,null);
    }
    @Test void citationsComeOnlyFromCandidateAndActiveDocumentVersion() throws Exception {
        UUID aDoc=UUID.randomUUID(), old=UUID.randomUUID(), current=UUID.randomUUID();
        UUID bDoc=UUID.randomUUID(), bVersion=UUID.randomUUID();
        jdbc.update("INSERT INTO documents(document_id,product_id,filename) VALUES (?,'BP-A','A.md'),(?,'BP-B','B.md')",aDoc,bDoc);
        for(var entry : Map.of(old,aDoc,current,aDoc,bVersion,bDoc).entrySet()) {
            int version=entry.getKey().equals(current) ? 2 : 1;
            jdbc.update("INSERT INTO document_versions(version_id,document_id,version,source_text,content_hash) VALUES (?,?,?,'test',?)",entry.getKey(),entry.getValue(),version,"0".repeat(64));
            jdbc.update("INSERT INTO ingestion_jobs(job_id,version_id,status) VALUES (?,?,'READY')",UUID.randomUUID(),entry.getKey());
            jdbc.update("INSERT INTO chunks(chunk_id,version_id,ordinal,heading,line_start,line_end,content) VALUES (?,?,1,'尺寸',1,1,?)",UUID.randomUUID(),entry.getKey(),"version="+entry.getKey());
        }
        jdbc.update("UPDATE documents SET active_version_id=? WHERE document_id=?",current,aDoc);
        jdbc.update("UPDATE documents SET active_version_id=? WHERE document_id=?",bVersion,bDoc);
        http.perform(post("/api/recommendations").contentType("application/json").content("{\"minPriceCny\":150,\"maxPriceCny\":200}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].citations.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].citations[0].product_id").value("BP-A"))
                .andExpect(jsonPath("$.candidates[0].citations[0].version").value(2))
                .andExpect(jsonPath("$.candidates[0].citations[0].content").value("version="+current));
    }
    private void check(Map<String,?> criteria, String expected, int count, String firstProduct) throws Exception {
        var result = http.perform(post("/api/recommendations").contentType("application/json").content(json.writeValueAsBytes(criteria)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(expected))
                .andExpect(jsonPath("$.candidates.length()").value(count));
        if(firstProduct != null) result.andExpect(jsonPath("$.candidates[0].product.product_id").value(firstProduct));
    }
}
