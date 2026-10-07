import { request } from './http'

/** 平台公司（全局数据，所有用户可读；无租户） */
export interface PlatformCompany {
  id: string
  name: string
  tier: string
  industry: string | null
  tags: string | null
}

export interface PlatformPosition {
  id: string
  name: string
}

/** 全部岗位（供下拉） */
export function listPositions(): Promise<PlatformPosition[]> {
  return request<PlatformPosition[]>({ url: '/companies/positions', method: 'get' })
}

/**
 * 查公司：`position` = 岗位名（按岗位反查「招这个岗位的公司」）；`q` = 公司名关键词；
 * 都缺省则返回全部上架公司。
 */
export function listCompanies(params: { position?: string; q?: string } = {}): Promise<PlatformCompany[]> {
  return request<PlatformCompany[]>({ url: '/companies', method: 'get', params })
}
