import { request } from './http'

/** 文档状态机：PENDING → PROCESSING → READY / FAILED */
export type DocumentStatus = 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED'

export interface KbDocument {
  id: string
  name: string
  docType: string
  status: string
  indexVersion: number | null
  chunkCount: number | null
  errorMessage: string | null
  createdAt: string | null
}

export interface IngestResult {
  documentId: string
  status: string
  chunkCount: number | null
  errorMessage: string | null
}

/** 文档列表；status 缺省为全部，按导入时间倒序 */
export function listDocuments(params?: { status?: string; limit?: number }): Promise<KbDocument[]> {
  return request<KbDocument[]>({ url: '/knowledge/documents', method: 'get', params })
}

/** 提交导入（异步）：落 PENDING 即返回，索引进度用 getDocument 轮询 */
export function createDocument(data: {
  name: string
  content: string
  docType?: string
  tags?: string
}): Promise<IngestResult> {
  return request<IngestResult>({ url: '/knowledge/documents', method: 'post', data })
}

export function getDocument(id: string): Promise<KbDocument> {
  return request<KbDocument>({ url: `/knowledge/documents/${id}`, method: 'get' })
}

export function reindexDocument(id: string): Promise<KbDocument> {
  return request<KbDocument>({ url: `/knowledge/documents/${id}/reindex`, method: 'post' })
}

/** 删除文档：级联删除向量与 Chunk */
export function deleteDocument(id: string): Promise<void> {
  return request<void>({ url: `/knowledge/documents/${id}`, method: 'delete' })
}
