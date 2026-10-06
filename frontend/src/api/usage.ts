import { request } from './http'

export interface ScenarioUsage {
  scenario: string
  calls: number
  chars: number
  promptTokens: number
  completionTokens: number
}

export interface DimensionUsage {
  dimension: string
  calls: number
  chars: number
  promptTokens: number
  completionTokens: number
  /** LLM 维度里「供应商未返回 token」的行数——部分已知的合计不能当成完整总量 */
  tokenUnavailable: number
  iterations: number
  toolCalls: number
  scenarios: ScenarioUsage[]
}

export interface StorageUsage {
  documents: number
  chars: number
}

export interface UsageSummary {
  dimensions: DimensionUsage[]
  storage: StorageUsage
}

/** 用量汇总（PRD-FP-10 可归集）。from / to 为 yyyy-MM-dd，缺省表示不限区间 */
export function getUsageSummary(params?: { from?: string; to?: string }): Promise<UsageSummary> {
  return request<UsageSummary>({ url: '/usage/summary', method: 'get', params })
}
