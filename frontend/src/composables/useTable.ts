import { ref, type Ref } from 'vue'
import type { ApiResponse, Page } from '@/api/types'

interface UseTableOptions<T> {
  /** 数据获取函数，接收 { page, size } 返回分页结果 */
  fetchFn: (params: { page: number; size: number }) => Promise<ApiResponse<Page<T>>>
  /** 初始每页条数 */
  defaultSize?: number
  /** 加载后是否自动滚动到顶部 */
  scrollToTop?: boolean
}

interface UseTableReturn<T> {
  data: Ref<T[]>
  loading: Ref<boolean>
  page: Ref<number>
  size: Ref<number>
  total: Ref<number>
  refresh: () => Promise<void>
  reset: () => void
}

export function useTable<T>(options: UseTableOptions<T>): UseTableReturn<T> {
  const { fetchFn, defaultSize = 10 } = options

  const data = ref<T[]>([]) as Ref<T[]>
  const loading = ref(false)
  const page = ref(1)
  const size = ref(defaultSize)
  const total = ref(0)

  async function refresh() {
    loading.value = true
    try {
      const resp = await fetchFn({ page: page.value, size: size.value })
      data.value = resp.data.records ?? []
      total.value = resp.data.total ?? 0
    } catch {
      // 错误已在 request 拦截器中统一处理
    } finally {
      loading.value = false
    }
  }

  function reset() {
    page.value = 1
    refresh()
  }

  return { data, loading, page, size, total, refresh, reset }
}