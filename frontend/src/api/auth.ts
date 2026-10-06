import { request } from './http'

/** 与后端 AuthController 的响应体一一对应 */
export interface TokenResponse {
  accessToken: string
}

export interface PrivacyNotice {
  version: string
  text: string
}

export function login(email: string, password: string): Promise<TokenResponse> {
  return request<TokenResponse>({
    url: '/auth/login',
    method: 'post',
    data: { email, password },
  })
}

/**
 * 注册。后端要求 privacyConsent 显式为 true（未同意会被 PRIVACY_CONSENT_REQUIRED 拒绝）；
 * UI 已在提交前确认用户勾选，这里直接置 true。
 */
export function register(email: string, password: string): Promise<TokenResponse> {
  return request<TokenResponse>({
    url: '/auth/register',
    method: 'post',
    data: { email, password, privacyConsent: true },
  })
}

/** 当期隐私政策与数据用途说明；注册前必须对用户可见 */
export function getPrivacyNotice(): Promise<PrivacyNotice> {
  return request<PrivacyNotice>({ url: '/auth/privacy-notice', method: 'get' })
}

/** 登出：服务端撤销当前令牌（jti 进 Redis 黑名单），原凭证立即失效 */
export function logout(): Promise<void> {
  return request<void>({ url: '/auth/logout', method: 'post' })
}
