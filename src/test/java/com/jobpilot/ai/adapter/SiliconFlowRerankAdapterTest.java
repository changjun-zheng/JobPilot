package com.jobpilot.ai.adapter;

import com.jobpilot.ai.RerankPort;
import com.jobpilot.config.RerankProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SiliconFlow /rerank 的响应解析（用 JDK 自带的 {@code HttpServer}，不引入 MockWebServer）。
 * 覆盖：解析 index/relevance_score 并按分数降序；HTTP 5xx 抛异常（由上层吞并降级）。
 */
class SiliconFlowRerankAdapterTest {

    private HttpServer server;
    private String baseUrl;
    private volatile int status = 200;
    private volatile String body =
            "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}]}";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/rerank", exchange -> {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void parsesAndSortsResultsByScore() {
        List<RerankPort.RerankHit> hits = adapter().rerank("查询", List.of("doc0", "doc1"), 2);

        assertThat(hits).extracting(RerankPort.RerankHit::index).containsExactly(1, 0);
        assertThat(hits.get(0).score()).isEqualTo(0.9);
    }

    @Test
    void httpErrorThrowsSoCallerCanDegrade() {
        status = 500;

        assertThatThrownBy(() -> adapter().rerank("查询", List.of("doc0"), 1))
                .isInstanceOf(RuntimeException.class);
    }

    private SiliconFlowRerankAdapter adapter() {
        return new SiliconFlowRerankAdapter(
                new RerankProperties(true, baseUrl, "sk-test", "BAAI/bge-reranker-v2-m3"),
                new SimpleClientHttpRequestFactory());
    }
}
