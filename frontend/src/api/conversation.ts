import { request } from './http'

export interface MessageStep {
  iteration: number
  kind: string
  name: string
  durationMs: number
  status: string
  summary: string | null
}

export type MessageRole = 'USER' | 'ASSISTANT'

export interface ConversationMessage {
  id: string
  role: MessageRole
  content: string
  steps: MessageStep[]
  traceId: string | null
  createdAt: string
}

/** 历史会话列表项 */
export interface ConversationSummary {
  id: string
  title: string
  updatedAt: string
}

export interface ConversationDetail {
  id: string
  title: string
  createdAt: string
  updatedAt: string
  messages: ConversationMessage[]
}

export function listConversations(limit = 50): Promise<ConversationSummary[]> {
  return request<ConversationSummary[]>({ url: '/conversations', method: 'get', params: { limit } })
}

export function getConversation(id: string): Promise<ConversationDetail> {
  return request<ConversationDetail>({ url: `/conversations/${id}`, method: 'get' })
}

export function deleteConversation(id: string): Promise<void> {
  return request<void>({ url: `/conversations/${id}`, method: 'delete' })
}
