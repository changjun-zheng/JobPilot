package com.jobpilot.ai.adapter;

import com.jobpilot.ai.EmbeddingPort;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI {@code EmbeddingModel} 适配器（本地 Ollama 与云端 OpenAI 兼容共用）。
 * <p>
 * 与 Chat 不同，Embedding 的调用**没有 provider 专属的 options**，所以不拆两套：哪个 provider 的
 * {@code EmbeddingModel} bean 生效由 {@code spring.ai.model.embedding} 决定（见 application.yml），
 * 本适配器只把它转成 {@link EmbeddingPort}。
 */
@Component
public class SpringAiEmbeddingAdapter implements EmbeddingPort {

    private final EmbeddingModel embeddingModel;

    public SpringAiEmbeddingAdapter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<Double> embed(String text) {
        EmbeddingResponse response = embeddingModel.embedForResponse(List.of(text));
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            throw new IllegalStateException("嵌入服务未返回向量");
        }
        float[] values = response.getResult().getOutput();
        List<Double> result = new ArrayList<>(values.length);
        for (float value : values) {
            result.add((double) value);
        }
        return result;
    }

    @Override
    public int dimension() {
        return embeddingModel.dimensions();
    }
}
