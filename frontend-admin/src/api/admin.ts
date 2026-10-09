import { request } from './http'

// ── 认证 ──────────────────────────────────────────────────────

export function adminLogin(username: string, password: string): Promise<{ accessToken: string }> {
  return request({ url: '/admin/auth/login', method: 'post', data: { username, password } })
}

// ── 公司 / 岗位 ───────────────────────────────────────────────

export interface AdminCompany {
  id: string
  name: string
  tier: string
  industry: string | null
  tags: string | null
  status: 'ACTIVE' | 'ARCHIVED'
}

export interface AdminPosition {
  id: string
  name: string
}

/** 公司列表，**含已下架**（管理视角看全量） */
export function listCompanies(): Promise<AdminCompany[]> {
  return request({ url: '/admin/platform/companies', method: 'get' })
}

export function createCompany(data: {
  name: string
  tier: string
  industry?: string
  tags?: string
}): Promise<AdminCompany> {
  return request({ url: '/admin/platform/companies', method: 'post', data })
}

export function updateCompany(
  id: string,
  data: { name?: string; tier?: string; industry?: string; tags?: string },
): Promise<AdminCompany> {
  return request({ url: `/admin/platform/companies/${id}`, method: 'put', data })
}

export function archiveCompany(id: string): Promise<void> {
  return request({ url: `/admin/platform/companies/${id}/archive`, method: 'post' })
}

export function activateCompany(id: string): Promise<AdminCompany> {
  return request({ url: `/admin/platform/companies/${id}/activate`, method: 'post' })
}

export function listPositions(): Promise<AdminPosition[]> {
  return request({ url: '/admin/platform/positions', method: 'get' })
}

export function createPosition(name: string): Promise<AdminPosition> {
  return request({ url: '/admin/platform/positions', method: 'post', data: { name } })
}

export function companyPositions(id: string): Promise<string[]> {
  return request({ url: `/admin/platform/companies/${id}/positions`, method: 'get' })
}

export function attachPosition(companyId: string, positionId: string): Promise<void> {
  return request({ url: `/admin/platform/companies/${companyId}/positions/${positionId}`, method: 'put' })
}

export function detachPosition(companyId: string, positionId: string): Promise<void> {
  return request({
    url: `/admin/platform/companies/${companyId}/positions/${positionId}`,
    method: 'delete',
  })
}

// ── 平台面经（文档）────────────────────────────────────────

export interface AdminDocument {
  id: string
  name: string
  docType: string
  tags: string | null
  sourceNote: string | null
  companyId: string | null
  status: string
  indexVersion: number | null
  chunkCount: number | null
  errorMessage: string | null
  createdAt: string | null
}

export function listDocuments(params: { status?: string; companyId?: string } = {}): Promise<
  AdminDocument[]
> {
  return request({ url: '/admin/platform/documents', method: 'get', params })
}

export function importDocument(data: {
  name: string
  docType: string
  companyId?: string
  tags?: string
  /** 来源/授权备注，**合规必填** */
  sourceNote: string
  content: string
}): Promise<AdminDocument> {
  return request({ url: '/admin/platform/documents', method: 'post', data })
}

export function reindexDocument(id: string): Promise<AdminDocument> {
  return request({ url: `/admin/platform/documents/${id}/reindex`, method: 'post' })
}

export function archiveDocument(id: string): Promise<void> {
  return request({ url: `/admin/platform/documents/${id}/archive`, method: 'post' })
}
