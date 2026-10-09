import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useAdminAuthStore } from '@/stores/auth'

/** 后端统一响应体（ApiResponse<T>） */
export interface ApiResponse<T> {
  success: boolean
  data: T
  error: { code: string; message: string } | null
  requestId: string
}

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE ?? '/api/v1',
  timeout: 60_000, // 平台面经导入是**同步索引**（切分+嵌入），比用户端请求慢，超时放宽
})

// 请求：带**管理令牌**。管理员不是租户，后端不会把它写进 UserContext（第二认证轴）。
http.interceptors.request.use((config) => {
  const token = useAdminAuthStore().token
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
      useAdminAuthStore().clear()
      ElMessage.error('管理登录已过期，请重新登录')
      router.push({ name: 'login' })
    } else {
      const message: string =
        error?.response?.data?.error?.message ?? error?.message ?? '网络错误'
      ElMessage.error(message)
    }
    return Promise.reject(error)
  },
)

/** 便捷封装：直接拿到 ApiResponse.data */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiResponse<T>>(config)
  return response.data.data
}

export default http
