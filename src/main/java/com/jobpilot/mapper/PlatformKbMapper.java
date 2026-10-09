package com.jobpilot.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.jobpilot.domain.KbChunkEntity;
import com.jobpilot.domain.KbDocumentEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 平台知识库内容的**管理写面**（第二认证轴上的写入口）。
 *
 * <h3>为什么单独一个 mapper，而不是往 KbDocumentMapper 上加方法</h3>
 * 租户侧那两个 mapper 的跨租户面是**只读**的；这里是**写**。分开成独立接口，
 * 「全部跨租户写 SQL」集中在一个文件里，审计时不必在几百行租户方法里挑刺。
 *
 * <h3>跨租户面论证（改动任何一条 SQL 都要重过）</h3>
 * 每条语句都带 {@code @InterceptorIgnore(tenantLine)}，但**每条 SQL 都在字面上硬写
 * {@code owner='PLATFORM'}**，其中 INSERT 连 {@code user_id} 都硬写为 {@code NULL}：
 * <ul>
 *   <li>写面：INSERT 只能造平台行；UPDATE/DELETE 的 WHERE 都含 {@code owner='PLATFORM'}，
 *       因此**改不到也删不到任何租户的行**——即使传入的是某个用户文档的 id；</li>
 *   <li>读面：SELECT 的 WHERE 同样硬写 {@code owner='PLATFORM'}，永不返回租户行。</li>
 * </ul>
 * 与 {@code KbDocumentMapper} 的队列方法同一纪律：<b>这是仅有的跨租户写面</b>。
 *
 * <h3>为什么不复用租户队列</h3>
 * 队列 worker 的语义是「认领行自带的 user_id 写回 UserContext 再处理」；平台行 user_id 为 NULL，
 * 撞 {@code TenantLineInnerInterceptor} 的 fail-closed。所以平台导入**同步**执行（管理操作低频），
 * 由 {@code platform.PlatformDocumentService} 编排，本 mapper 只负责落库。
 */
public interface PlatformKbMapper {

    // ── kb_document ────────────────────────────────────────────

    /** 落一行平台文档。{@code user_id} 与 {@code owner} 硬写在 SQL 里——本语句造不出租户行 */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT INTO kb_document (id, user_id, owner, company_id, name, doc_type, tags, source_note,
                                     content, status, index_version, chunk_count, retry_count)
            VALUES (#{id}, NULL, 'PLATFORM', #{companyId}, #{name}, #{docType}, #{tags}, #{sourceNote},
                    #{content}, #{status}, #{indexVersion}, #{chunkCount}, 0)
            """)
    int insertPlatformDocument(KbDocumentEntity doc);

    /** 按 id 取平台文档（含 content，供重索引）；只命中平台行 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM kb_document WHERE id = #{id} AND owner = 'PLATFORM'")
    KbDocumentEntity selectPlatformDocument(@Param("id") String id);

    /** 平台文档列表（管理界面）；不取 content（列表不拖大字段） */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
            SELECT id, user_id, owner, company_id, name, doc_type, tags, source_note, status,
                   index_version, chunk_count, error_message, created_at, updated_at
            FROM kb_document
            WHERE owner = 'PLATFORM'
              <if test="status != null and status != ''"> AND status = #{status} </if>
              <if test="companyId != null and companyId != ''"> AND company_id = #{companyId} </if>
            ORDER BY created_at DESC
            LIMIT #{limit}
            </script>
            """)
    List<KbDocumentEntity> selectPlatformDocuments(@Param("status") String status,
                                                   @Param("companyId") String companyId,
                                                   @Param("limit") int limit);

    /** 索引完成：READY + chunk 数。{@code status <> 'ARCHIVED'} 兜住并发下架（下架是终态） */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE kb_document SET status = 'READY', chunk_count = #{chunkCount},
                   error_message = NULL, updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND owner = 'PLATFORM' AND status <> 'ARCHIVED'
            """)
    int markPlatformDocumentReady(@Param("id") String id, @Param("chunkCount") int chunkCount);

    /** 索引失败：FAILED + 原因（不重试——管理端同步路径，失败即返回给运营者） */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE kb_document SET status = 'FAILED', error_message = #{errorMessage},
                   updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND owner = 'PLATFORM' AND status <> 'ARCHIVED'
            """)
    int markPlatformDocumentFailed(@Param("id") String id, @Param("errorMessage") String errorMessage);

    /** 重索引前置：重置回 PENDING。ARCHIVED 是终态，不允许被重索引复活 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE kb_document SET status = 'PENDING', chunk_count = 0,
                   error_message = NULL, updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND owner = 'PLATFORM' AND status <> 'ARCHIVED'
            """)
    int resetPlatformDocumentForReindex(@Param("id") String id);

    /**
     * 下架：status → ARCHIVED（终态）。<b>只翻状态列</b>——Chunk 与向量由服务层清理，
     * 文档行本身保留（审计需要「这篇面经存在过、何时被下架」）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE kb_document SET status = 'ARCHIVED', updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND owner = 'PLATFORM'
            """)
    int archivePlatformDocument(@Param("id") String id);

    // ── kb_chunk ───────────────────────────────────────────────

    /** 落一行平台 Chunk。{@code user_id} 与 {@code owner} 硬写在 SQL 里 */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT INTO kb_chunk (vector_id, document_id, user_id, owner, doc_name, doc_type,
                                  section_path, seq, text, char_start, char_end, index_version)
            VALUES (#{vectorId}, #{documentId}, NULL, 'PLATFORM', #{docName}, #{docType},
                    #{sectionPath}, #{seq}, #{text}, #{charStart}, #{charEnd}, #{indexVersion})
            """)
    int insertPlatformChunk(KbChunkEntity chunk);

    /** 删某平台文档的全部 Chunk（重索引/下架用）。WHERE 硬写 owner，删不到租户行 */
    @InterceptorIgnore(tenantLine = "true")
    @Delete("DELETE FROM kb_chunk WHERE document_id = #{documentId} AND owner = 'PLATFORM'")
    int deletePlatformChunks(@Param("documentId") String documentId);
}
