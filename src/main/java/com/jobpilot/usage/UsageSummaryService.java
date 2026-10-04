package com.jobpilot.usage;

import com.jobpilot.mapper.KbDocumentMapper;
import com.jobpilot.mapper.UsageRecordMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 用量汇总（FP-10：用量数据用户可见）。
 * <p>
 * 租户过滤与全项目读路径一致：不接收 {@code userId} 参数，由租户拦截器按
 * {@code UserContext} 注入（fail-closed，缺失即抛），不靠调用方自觉。
 * <p>
 * 三个计量维度**零填充**输出（与投递统计的「Σ==total」同一动机）：没发生的维度也要出现，
 * 调用方不需要对「缺失」和「为零」做两种解释。{@code tokenUnavailable} 单独透出——
 * 它是「部分已知的合计」与「完整合计」之间差距的显式声明，不能悄悄吞掉。
 */
@Service
public class UsageSummaryService {

    private final UsageRecordMapper usageRecordMapper;
    private final KbDocumentMapper documentMapper;

    public UsageSummaryService(UsageRecordMapper usageRecordMapper, KbDocumentMapper documentMapper) {
        this.usageRecordMapper = usageRecordMapper;
        this.documentMapper = documentMapper;
    }

    /** 单个维度的区间汇总；{@code scenarios} 为空表示该维度区间内没有计量行 */
    public record DimensionUsage(
            String dimension,
            long calls,
            long chars,
            long promptTokens,
            long completionTokens,
            long tokenUnavailable,
            long iterations,
            long toolCalls,
            List<ScenarioUsage> scenarios
    ) {
    }

    /** 维度下的场景细分；聚合口径与所属维度一致 */
    public record ScenarioUsage(
            String scenario,
            long calls,
            long chars,
            long promptTokens,
            long completionTokens
    ) {
    }

    /** 存储用量（PRD-FP-10 维度四）：对 kb_document 现查的当前态，不是事件流 */
    public record StorageUsage(long documents, long chars) {
    }

    public record UsageSummary(List<DimensionUsage> dimensions, StorageUsage storage) {
    }

    /**
     * 区间汇总。{@code from}/{@code to} 为日期（含当天两端）；缺省为全部历史。
     */
    public UsageSummary summary(LocalDate from, LocalDate to) {
        LocalDateTime start = from == null ? LocalDateTime.of(1970, 1, 1, 0, 0) : from.atStartOfDay();
        LocalDateTime end = to == null ? LocalDateTime.now().plusDays(1) : to.plusDays(1).atStartOfDay();
        List<UsageAggregateRow> rows = usageRecordMapper.aggregate(start, end);

        List<DimensionUsage> dimensions = new ArrayList<>();
        for (UsageDimension dimension : UsageDimension.values()) {
            List<UsageAggregateRow> matches = rows.stream()
                    .filter(row -> dimension.name().equals(row.getDimension()))
                    .toList();
            List<ScenarioUsage> scenarios = matches.stream()
                    .map(row -> new ScenarioUsage(row.getScenario(), row.getCallCount(), row.getCharCount(),
                            row.getPromptTokens(), row.getCompletionTokens()))
                    .toList();
            dimensions.add(new DimensionUsage(
                    dimension.name(),
                    sum(matches, UsageAggregateRow::getCallCount),
                    sum(matches, UsageAggregateRow::getCharCount),
                    sum(matches, UsageAggregateRow::getPromptTokens),
                    sum(matches, UsageAggregateRow::getCompletionTokens),
                    sum(matches, UsageAggregateRow::getTokenUnavailable),
                    sum(matches, UsageAggregateRow::getIterations),
                    sum(matches, UsageAggregateRow::getToolCalls),
                    scenarios));
        }

        StorageUsageRow storage = documentMapper.selectStorageUsage();
        StorageUsage storageUsage = storage == null
                ? new StorageUsage(0, 0)
                : new StorageUsage(storage.getDocumentCount(), storage.getCharCount());
        return new UsageSummary(List.copyOf(dimensions), storageUsage);
    }

    private long sum(List<UsageAggregateRow> rows, java.util.function.ToLongFunction<UsageAggregateRow> getter) {
        return rows.stream().mapToLong(getter).sum();
    }
}
