package com.jobpilot.knowledge;

import com.jobpilot.ai.CollectionNotFoundException;
import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 启动自检：确认配置的 Chroma 集合真实存在，并校验向量维度是否与当前 embedding 模型一致。
 * <p>
 * 为什么用 {@link ApplicationRunner} 而不是 {@code @PostConstruct}：本检查要调用
 * {@link VectorStorePort} 与 {@link EmbeddingPort} 两个 Bean，必须等容器全部就绪。
 * <p>
 * 失败语义（本检查的核心）：
 * <ul>
 *   <li><b>集合不存在</b>——永久性配置错误，不会自愈，<b>抛出异常阻断启动</b>，
 *       避免应用以"看似正常"的姿态长期运行在降级模式；</li>
 *   <li><b>向量库不可达</b>——临时故障，<b>仅告警</b>，保留关键词降级能力（ARCHITECTURE.md §8）；</li>
 *   <li><b>维度不一致</b>——同样是永久性错误（换过 embedding 模型但未重建索引），阻断启动。</li>
 * </ul>
 */
@Component
public class ChromaStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChromaStartupCheck.class);

    private final VectorStorePort vectorStore;
    private final EmbeddingPort embeddingPort;
    private final String configuredEmbeddingModel;

    public ChromaStartupCheck(VectorStorePort vectorStore, EmbeddingPort embeddingPort, RagProperties props) {
        this.vectorStore = vectorStore;
        this.embeddingPort = embeddingPort;
        this.configuredEmbeddingModel = props.embeddingModel();
    }

    @Override
    public void run(ApplicationArguments args) {
        Optional<VectorStorePort.CollectionInfo> found;
        try {
            found = vectorStore.collectionInfo();
        } catch (CollectionNotFoundException e) {
            // 永久性配置错误：早失败远胜晚失败
            throw new IllegalStateException("Chroma 集合自检未通过。" + e.getMessage()
                    + "；处理方式：确认 jobpilot.rag.chroma-collection-id 是否指向真实存在的集合", e);
        } catch (Exception e) {
            // 临时故障：保留降级能力，不阻断启动
            log.warn("向量库不可达，跳过集合自检；检索将降级为关键词模式。VECTOR_STORE_DEGRADED", e);
            return;
        }

        if (found.isEmpty()) {
            // 未配置 collection id —— 无从自检，但这不是错误（可能是首次运行、尚未建集合）
            log.warn("未配置 jobpilot.rag.chroma-collection-id，跳过集合自检。"
                    + "首次导入文档时将自动创建集合，之后建议把返回的集合 UUID 写入该配置，"
                    + "否则重启后会因按名查询缺陷而无法复用集合。");
            return;
        }

        VectorStorePort.CollectionInfo info = found.get();
        log.info("Chroma 集合自检通过：{}（id={}）", info.name(), info.id());

        checkDimension(info);
        checkEmbeddingModel(info);
    }

    /**
     * 嵌入模型一致性：集合 metadata 里记着建集合时用的 {@code embedding_model}，与当前配置不一致
     * 说明「换了嵌入模型但没重建索引」——**维度相同时维度检查漏得掉**（如 bge-m3 → bge-large-zh 都是 1024），
     * 但两套向量语义不兼容、混在一起会让检索静默变差。属永久性错误，阻断启动并提示重建。
     * <p>老集合没有这个 metadata（无法验证）→ 只告警，不阻断。
     */
    private void checkEmbeddingModel(VectorStorePort.CollectionInfo info) {
        Object marker = info.metadata() == null ? null : info.metadata().get("embedding_model");
        if (marker == null) {
            log.warn("集合 {} 未记录 embedding_model，无法校验嵌入模型是否与配置一致——"
                    + "若刚换过嵌入模型，请执行 POST /api/v1/knowledge/documents/reindex-all 重建索引。",
                    info.name());
            return;
        }
        String recorded = String.valueOf(marker);
        if (configuredEmbeddingModel != null && !configuredEmbeddingModel.equals(recorded)) {
            throw new IllegalStateException(String.format(
                    "嵌入模型不一致：集合里是 %s，当前配置是 %s。不同模型的向量语义不兼容，"
                            + "即使维度相同也会让检索静默劣化。处理方式："
                            + "POST /api/v1/knowledge/documents/reindex-all 用当前模型重建索引"
                            + "（或换一个 chroma-collection-id 指向新集合）。",
                    recorded, configuredEmbeddingModel));
        }
        log.info("嵌入模型校验通过：{}", recorded);
    }

    /** 维度不一致 = 换过 embedding 模型但没重建索引，属于永久性错误，同样阻断启动 */
    private void checkDimension(VectorStorePort.CollectionInfo info) {
        Integer collectionDim = info.dimension();
        if (collectionDim == null) {
            log.info("集合 {} 尚未确立向量维度（空集合），跳过维度校验", info.name());
            return;
        }
        int currentDim = embeddingPort.dimension();
        if (collectionDim != currentDim) {
            throw new IllegalStateException(String.format(
                    "向量维度不一致：collection=%d，embedding=%d。"
                            + "通常是换过 embedding 模型但未重建索引；"
                            + "处理方式：提升 index_version 后重新导入文档。",
                    collectionDim, currentDim));
        }
        log.info("向量维度校验通过：{} 维", currentDim);
    }
}
