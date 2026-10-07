package com.jobpilot.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.usage.StorageUsageRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface KbDocumentMapper extends BaseMapper<KbDocumentEntity> {

    /**
     * 存储用量（PRD-FP-10 维度四：文档数、字符数）。
     * <p>
     * 刻意<b>不入 usage_record 事件行</b>：存储是「当前态」不是「事件流」，现查永远准确，
     * 也不会跟删除时序赛跑。user_id 过滤由租户拦截器追加（本 SQL 不写，隔离不靠调用方）。
     */
    @Select("""
            SELECT COUNT(*) AS document_count,
                   COALESCE(SUM(CHAR_LENGTH(content)), 0) AS char_count
            FROM kb_document
            """)
    StorageUsageRow selectStorageUsage();

    /**
     * 以下四个方法是 I-1c 的 DB 队列操作，运行在 worker 线程上——
     * 那里没有 UserContext，租户拦截器 fail-closed 会直接抛异常，
     * 因此显式声明 {@code @InterceptorIgnore(tenantLine)}。
     * <p>
     * 租户安全不受影响：认领的是「行自带的 user_id」，处理阶段 worker 会把它
     * 写回 {@code UserContext}，此后所有受租户拦截器保护的读写照常注入过滤条件。
     * 这四条 SQL 是仅有的跨租户面，改动任何一条都要重新过一遍这个论证。
     */

    /**
     * 认领候选：PENDING 且到期，按先来先服务。
     * {@code FOR UPDATE SKIP LOCKED} 是队列语义的核心——多个 worker（线程或实例）
     * 并发扫描时，已锁定的行被直接跳过而不是阻塞，天然不重复认领。
     * 必须在事务内调用，锁随认领事务提交而释放。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM kb_document
            WHERE status = 'PENDING'
              AND (next_retry_at IS NULL OR next_retry_at <= #{now})
            ORDER BY created_at
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<KbDocumentEntity> selectClaimCandidates(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 每租户在途（PROCESSING）计数，认领前比对 maxPerTenant，防单租户占满全部 worker */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) FROM kb_document WHERE user_id = #{userId} AND status = 'PROCESSING'")
    int countProcessingByTenant(@Param("userId") String userId);

    /** CAS 式认领：仅当行仍是 PENDING 时翻成 PROCESSING；返回 0 说明被并发改走，放弃该候选 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE kb_document SET status = 'PROCESSING' WHERE id = #{id} AND status = 'PENDING'")
    int markProcessing(@Param("id") String id);

    /** 启动接管：JVM 重启遗留的 PROCESSING 行按 updated_at 判僵死，重置回 PENDING 立即可认领 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE kb_document SET status = 'PENDING', next_retry_at = #{now}
            WHERE status = 'PROCESSING' AND updated_at < #{cutoff}
            """)
    int resetStaleProcessing(@Param("now") LocalDateTime now, @Param("cutoff") LocalDateTime cutoff);

    /**
     * 给定 id 里**属于平台、且状态为 READY** 的那批文档 id（回捞平台 Chunk 时校验其文档已就绪）。
     * <p>
     * 同上面的纪律：{@code @InterceptorIgnore(tenantLine)} + SQL 里硬写 {@code owner='PLATFORM'}，
     * <b>只读平台行，永不返回任何租户的行</b>——不构成跨租户泄漏面。改动本条 SQL 要重过这个论证。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
            SELECT id FROM kb_document
            WHERE owner = 'PLATFORM' AND status = 'READY' AND id IN
            <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>
            </script>
            """)
    List<String> selectPlatformReadyDocumentIds(@Param("ids") Collection<String> ids);
}
