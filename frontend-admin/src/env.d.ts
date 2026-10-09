/// <reference types="vite/client" />

// .vue 单文件组件的类型声明由 vue-tsc / Volar 提供；此处仅补 env 变量的类型。
interface ImportMetaEnv {
  /** 可选：覆盖 API 基地址。缺省用 '/api/v1'（开发期经 Vite 代理到后端） */
  readonly VITE_API_BASE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
