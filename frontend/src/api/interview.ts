import { request } from './http'

export interface TurnResult {
  sessionId: string
  phase: string
  round: number
  totalRounds: number
  status: string
  /** finished 为 true 时为 null，前端应去取报告 */
  question: string | null
  finished: boolean
}

export interface ReportDimension {
  name: string
  comment: string
  score: number | null
}

export interface ReportWeakness {
  content: string
  confidence: number | null
  note: string | null
}

export interface InterviewReport {
  summary: string
  dimensions: ReportDimension[]
  weaknesses: ReportWeakness[]
}

export interface ReportResult {
  sessionId: string
  report: InterviewReport
  draftId: string | null
  candidateIds: string[]
  status: string
}

export interface InterviewMessage {
  role: 'INTERVIEWER' | 'CANDIDATE'
  phase: string
  round: number
  content: string
}

export interface SessionDetail {
  sessionId: string
  company: string
  position: string | null
  tier: string
  difficulty: string
  phase: string
  round: number
  totalRounds: number
  status: string
  messages: InterviewMessage[]
}

export interface SessionSummary {
  sessionId: string
  company: string
  position: string | null
  tier: string
  phase: string
  round: number
  totalRounds: number
  status: string
}

/** 开一场面试并返回第一题 */
export function startInterview(data: {
  company: string
  position?: string
  tier?: string
  difficultyOverride?: string
}): Promise<TurnResult> {
  return request<TurnResult>({ url: '/interview/sessions', method: 'post', data })
}

export function answerInterview(sessionId: string, answer: string): Promise<TurnResult> {
  return request<TurnResult>({
    url: `/interview/sessions/${sessionId}/answers`,
    method: 'post',
    data: { answer },
  })
}

/** 提前收尾（走完三轮会自动收尾，无需手动调用） */
export function finishInterview(sessionId: string): Promise<ReportResult> {
  return request<ReportResult>({ url: `/interview/sessions/${sessionId}/finish`, method: 'post' })
}

export function getReport(sessionId: string): Promise<ReportResult> {
  return request<ReportResult>({ url: `/interview/sessions/${sessionId}/report`, method: 'get' })
}

export function getSession(sessionId: string): Promise<SessionDetail> {
  return request<SessionDetail>({ url: `/interview/sessions/${sessionId}`, method: 'get' })
}

export function listSessions(limit = 50): Promise<SessionSummary[]> {
  return request<SessionSummary[]>({ url: '/interview/sessions', method: 'get', params: { limit } })
}
