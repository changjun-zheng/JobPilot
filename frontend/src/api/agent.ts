import { request } from './http'

/** 一次 run 的逐步摘要（对应后端 AgentRunner.Step / AgentController.StepView） */
export interface AgentStep {
  iteration: number
  kind: string
  name: string
  durationMs: number
  status: string
  summary: string | null
}

export interface AgentRunResult {
  traceId: string
  conversationId: string
  answer: string
  finishReason: string
  steps: AgentStep[]
  /** 待审批草稿 ID；非空表示本轮因 HITL 结束，副作用尚未执行 */
  draftIds: string[]
}

/**
 * 发起一次对话。
 * 首轮不传 conversationId（服务端新建并返回），之后必须回传服务端给的 id。
 */
export function runAgent(message: string, conversationId?: string): Promise<AgentRunResult> {
  return request<AgentRunResult>({
    url: '/agent/run',
    method: 'post',
    data: { message, conversationId: conversationId ?? null },
  })
}

export interface ApprovalResult {
  draftId: string
  status: string
  resultRef: string | null
  writtenMemoryIds: string[]
}

/**
 * 审批草稿并执行副作用。
 * 批量草稿（如记忆候选）可带 selectedCandidateIds 做**部分审批**；不传即整批通过。
 */
export function approveDraft(draftId: string, selectedCandidateIds?: string[]): Promise<ApprovalResult> {
  return request<ApprovalResult>({
    url: `/agent/approvals/${draftId}/approve`,
    method: 'post',
    data: selectedCandidateIds ? { selectedCandidateIds } : {},
  })
}
