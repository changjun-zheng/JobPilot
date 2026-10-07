package com.jobpilot.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobpilot.domain.KbChunkEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 知识库 Chunk。
 *
 * <h3>两个平台读方法——受控的跨租户面</h3>
 * 平台行（{@code owner='PLATFORM'}）的 {@code user_id} 为 NULL，租户拦截器注入的 {@code user_id=<t>}
 * 会把它们滤掉，所以显式 {@code @InterceptorIgnore(tenantLine)} + SQL 里**硬写 {@code owner='PLATFORM'}**。
 * <p>
 * <b>论证</b>：这两条 SQL <b>只读平台行，永不返回任何租户的行</b>——因此不构成跨租户泄漏面。
 * 与 {@code KbDocumentMapper} 那四条队列方法同一纪律：<b>改动这两条 SQL 都要重新过一遍这个论证。</b>
 */
public interface KbChunkMapper extends BaseMapper<KbChunkEntity> {

    /** 按 vector_id 取平台 Chunk（向量检索回捞平台内容用） */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
            SELECT * FROM kb_chunk
            WHERE owner = 'PLATFORM' AND vector_id IN
            <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>
            </script>
            """)
    List<KbChunkEntity> selectPlatformChunksByIds(@Param("ids") Collection<String> ids);

    /** 平台 Chunk 的关键词检索（检索降级路径用）；只取 READY 平台文档的 Chunk，{@code companyIds} 非空时按公司收窄 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
            SELECT c.* FROM kb_chunk c JOIN kb_document d ON d.id = c.document_id
            WHERE c.owner = 'PLATFORM' AND d.status = 'READY'
              <if test="docType != null and docType != ''"> AND c.doc_type = #{docType} </if>
              <if test="companyIds != null and companyIds.size() > 0">
                AND d.company_id IN
                <foreach item='cid' collection='companyIds' open='(' separator=',' close=')'>#{cid}</foreach>
              </if>
              AND (
                <foreach item='kw' collection='keywords' separator=' OR '>
                  c.text LIKE CONCAT('%', #{kw}, '%')
                </foreach>
              )
            ORDER BY c.document_id, c.seq
            LIMIT #{limit}
            </script>
            """)
    List<KbChunkEntity> selectPlatformChunksByKeywords(@Param("keywords") List<String> keywords,
                                                       @Param("docType") String docType,
                                                       @Param("companyIds") List<String> companyIds,
                                                       @Param("limit") int limit);
}
