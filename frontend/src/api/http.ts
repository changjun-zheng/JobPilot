import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'

/** 后端统一响应体（ApiResponse<T>）：成功/失败都带 requestId，便于凭它对齐服务端日志 */
export interface ApiResponse<T> {
  success: boolean
  data: T
  error: { code: string; message: string } | null
  requestId: string
}

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE ?? '/api/v1',
  timeout: 30_000,
})

// 请求：带上 JWT。租户身份由后端从 token 解析，前端不传 userId（架构不变量）。
http.interceptors.request.use((config) => {
  const token = useAuthStore().token
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应：解包 ApiResponse；401 清登录态并回登录页
http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse<unknown> | undefined
    if (body && body.success === false) {
      ElMessage.error(body.error?.message ?? '请求失败')
      return Promise.reject(new Error(body.error?.message ?? '请求失败'))
    }
    return response
  },
  (error) => {
    const status: number | undefined = error?.response?.status
    if (status === 401) {
      useAuthStore().clear()
      ElMessage.error('登录已过期，请重新登录')
      router.push({ name: 'login' })
    } else {
      const message: string =
        error?.response?.data?.error?.message ?? error?.message ?? '网络错误'
      ElMessage.error(message)
    }
    return Promise.reject(error)
  },
)

/** 便捷封装：直接拿到 ApiResponse.data，调用方不再关心信封 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiResponse<T>>(config)
  return response.data.data
}

export default http
