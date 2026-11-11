/**
 * 后端 ES 存储的是 UTC 时间（如 2026-08-23T18:40:00.346Z），
 * 直接显示会比北京时间慢 8 小时，导致用户按显示时间去搜索时对不上。
 * 统一转成本地时间 "yyyy-MM-dd HH:mm:ss" 显示。
 */
export function formatUtcTime(value: string | null | undefined): string {
  if (!value) return '-'
  // 已是本地格式则原样返回
  if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(value)) return value
  const d = new Date(value)
  if (isNaN(d.getTime())) return value
  const p = (n: number) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ` +
    `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
  )
}
