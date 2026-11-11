<template>
  <div class="view">
    <PageHeader
      title="系统监控"
      :subtitle="`网关运行时指标 · 5s 自动刷新 · 上次更新 ${lastUpdated || '--:--:--'}`"
    >
      <template #action>
        <span class="micro-label live-tag">
          <span class="status-dot" :class="loading ? 'warn' : 'online'" />
          {{ loading ? 'FETCHING' : 'LIVE' }}
        </span>
      </template>
    </PageHeader>

    <!-- 顶层状态：运行时长 / 在途请求 / 活跃流 / 累计调用 -->
    <div class="mon-stats">
      <StatCard label="运行时长" :value="fmtDuration(metrics?.uptimeSeconds ?? 0)" icon="Timer" color="#0f766e" />
      <StatCard label="在途请求" :value="metrics?.calls.inFlight ?? 0" icon="Loading" color="#2563eb" />
      <StatCard label="活跃 SSE 流" :value="metrics?.calls.activeStreams ?? 0" icon="DataLine" color="#0891b2" />
      <StatCard label="累计调用" :value="metrics?.calls.totalCalls ?? 0" icon="Histogram" color="#16a34a" />
    </div>

    <div class="mon-grid">
      <!-- 调用吞吐：60s 逐秒曲线 -->
      <section class="mon-card span-12">
        <header class="mon-card-head">
          <span class="micro-label">调用吞吐</span>
          <span class="mono dim">
            qps10s <b>{{ metrics?.calls.qps10s ?? '0' }}</b>
            &nbsp;·&nbsp; qps60s <b>{{ metrics?.calls.qps60s ?? '0' }}</b>
          </span>
        </header>
        <svg class="spark" viewBox="0 0 600 64" preserveAspectRatio="none">
          <polygon :points="sparkPoints.area" fill="rgba(20,184,166,0.12)" />
          <polyline :points="sparkPoints.line" fill="none" stroke="#14b8a6" stroke-width="2" />
        </svg>
        <p class="mono dim spark-axis">-60s → now · 窗口峰值 {{ sparkPoints.max }} 次/秒</p>
      </section>

      <!-- 运行时 / JVM -->
      <section class="mon-card span-4" :class="jvmLevel !== 'ok' ? 'alert-' + jvmLevel : ''">
        <header class="mon-card-head">
          <span class="micro-label">运行时 / JVM</span>
          <span v-if="jvmLevel !== 'ok'" class="alert-badge" :class="jvmLevel">{{ jvmLevel === 'critical' ? 'ALERT >95%' : 'WARN >80%' }}</span>
        </header>
        <div class="kv"><span>heap used / committed</span><b class="mono">{{ mb(jvm?.heapUsed) }} / {{ mb(jvm?.heapCommitted) }} MB</b></div>
        <div class="bar"><i :style="{ width: heapPct + '%', background: barColor(heapPct) }" /></div>
        <div class="kv"><span>heap max</span><b class="mono">{{ mb(jvm?.heapMax) }} MB · {{ heapPct }}%</b></div>
        <div class="kv dashed"><span>non-heap used</span><b class="mono">{{ mb(jvm?.nonHeapUsed) }} MB</b></div>
        <div class="kv"><span>线程 live / peak</span><b class="mono">{{ threads?.threadCount ?? '-' }} / {{ threads?.peakThreadCount ?? '-' }}</b></div>
        <div class="kv"><span>daemon 线程</span><b class="mono">{{ threads?.daemonThreadCount ?? '-' }}</b></div>
        <div class="kv"><span>CPU 核数</span><b class="mono">{{ threads?.availableProcessors ?? '-' }}</b></div>
      </section>

      <!-- 批量写队列水位 -->
      <section class="mon-card span-4" :class="queueLevel !== 'ok' ? 'alert-' + queueLevel : ''">
        <header class="mon-card-head">
          <span class="head-group">
            <span class="micro-label">批量写队列</span>
            <span v-if="queueLevel !== 'ok'" class="alert-badge" :class="queueLevel">{{ queueLevel === 'critical' ? 'ALERT >95%' : 'WARN >80%' }}</span>
          </span>
          <span class="mono dim">flush {{ batch?.flushIntervalMs ?? '-' }}ms</span>
        </header>
        <div class="kv"><span>会话消息队列</span><b class="mono">{{ batch?.messageQueueSize ?? '-' }} / {{ batch?.queueCapacity ?? '-' }}</b></div>
        <div class="bar"><i :style="{ width: msgPct + '%', background: barColor(msgPct) }" /></div>
        <div class="kv dashed"><span>日汇总队列</span><b class="mono">{{ batch?.billingQueueSize ?? '-' }} / {{ batch?.queueCapacity ?? '-' }}</b></div>
        <div class="bar"><i :style="{ width: billPct + '%', background: barColor(billPct) }" /></div>
        <p class="mono dim hint">水位持续 &gt;80% 表示下游 MySQL 写入跟不上（每 {{ batch?.flushIntervalMs ?? 500 }}ms 攒批落库）</p>
      </section>

      <!-- 依赖探活 -->
      <section class="mon-card span-4">
        <header class="mon-card-head">
          <span class="micro-label">依赖探活</span>
          <span class="mono dim">v{{ metrics?.version ?? '2.0.0' }}</span>
        </header>
        <div class="kv">
          <span><span class="status-dot" :style="{ background: statusColor(metrics?.redis.status) }" /> Redis</span>
          <b class="mono">{{ metrics ? metrics.redis.status.toUpperCase() : '-' }} · {{ metrics?.redis.latencyMs ?? '-' }}ms</b>
        </div>
        <div class="kv dashed">
          <span><span class="status-dot" :style="{ background: statusColor(metrics?.db.status) }" /> MySQL</span>
          <b class="mono">{{ metrics ? metrics.db.status.toUpperCase() : '-' }} · {{ metrics?.db.latencyMs ?? '-' }}ms</b>
        </div>
        <p class="mono dim hint">PING / SELECT 1 实测；down 时接口降级标注，监控页展示红点</p>
      </section>

      <!-- 模型健康（复用 /admin/routing/analysis） -->
      <section class="mon-card span-12">
        <header class="mon-card-head">
          <span class="micro-label">模型健康</span>
          <span class="mono dim">{{ models.length }} MODELS</span>
        </header>
        <p v-if="!models.length" class="mono dim empty-note">NO MODELS REGISTERED</p>
        <div v-for="m in models" :key="m.modelCode" class="model-row">
          <span class="status-dot" :style="{ background: modelStatus(m).color }" />
          <span class="mono model-code">{{ m.modelCode }}</span>
          <span class="model-name" :title="m.modelName">{{ m.modelName }}</span>
          <span class="mono dim tier">{{ m.modelTier }}</span>
          <span class="mono">health <b :style="{ color: modelStatus(m).color }">{{ m.healthScore }}</b></span>
          <span class="mono dim">p90 {{ m.p90LatencyMs !== null ? m.p90LatencyMs + 'ms' : '-' }}</span>
          <span class="micro-label" :style="{ color: modelStatus(m).color }">{{ modelStatus(m).text }}</span>
          <span v-if="m.blacklistReason" class="mono dim reason" :title="m.blacklistReason">{{ m.blacklistReason }}</span>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatCard from '@/components/common/StatCard.vue'
import { getRoutingAnalysis, getRuntimeMetrics } from '@/api/admin'
import type { RoutingAnalysisItem, RuntimeMetrics } from '@/api/types'

/** 轮询周期：与后端 60s 逐秒窗口展示粒度匹配 */
const REFRESH_MS = 5000

const metrics = ref<RuntimeMetrics | null>(null)
const models = ref<RoutingAnalysisItem[]>([])
const loading = ref(true)
const lastUpdated = ref('')
let timer: number | undefined

const jvm = computed(() => metrics.value?.jvm)
const threads = computed(() => metrics.value?.threads)
const batch = computed(() => metrics.value?.batchWriter)

const heapPct = computed(() => {
  const j = jvm.value
  if (!j || !j.heapMax) return 0
  return Math.min(100, Math.round((j.heapUsed / j.heapMax) * 100))
})

const msgPct = computed(() => {
  const b = batch.value
  if (!b || !b.queueCapacity) return 0
  return Math.min(100, Math.round((b.messageQueueSize / b.queueCapacity) * 100))
})

const billPct = computed(() => {
  const b = batch.value
  if (!b || !b.queueCapacity) return 0
  return Math.min(100, Math.round((b.billingQueueSize / b.queueCapacity) * 100))
})

/** 告警线：卡片级阈值。>80% 琥珀描边，>95% 红色描边（与进度条配色一致） */
const ALERT_WARN = 80
const ALERT_CRIT = 95

type AlertLevel = 'ok' | 'warn' | 'critical'

function alertLevel(pct: number): AlertLevel {
  if (pct > ALERT_CRIT) return 'critical'
  if (pct > ALERT_WARN) return 'warn'
  return 'ok'
}

function barColor(pct: number): string {
  if (pct > ALERT_CRIT) return '#dc2626'
  if (pct > ALERT_WARN) return '#d97706'
  return '#0d9488'
}

const jvmLevel = computed<AlertLevel>(() => alertLevel(heapPct.value))
const queueLevel = computed<AlertLevel>(() => alertLevel(Math.max(msgPct.value, billPct.value)))

/** 60s 逐秒 sparkline：line 折线点串 + area 填充多边形 */
const sparkPoints = computed(() => {
  const data = metrics.value?.calls.perSecond ?? []
  const w = 600
  const h = 64
  const max = Math.max(1, ...data)
  const step = data.length > 1 ? w / (data.length - 1) : w
  const pts = data.map((c, i) => `${(i * step).toFixed(1)},${(h - 4 - (c / max) * (h - 10)).toFixed(1)}`)
  return {
    line: pts.join(' '),
    area: `0,${h} ${pts.join(' ')} ${w},${h}`,
    max,
  }
})

async function refresh(): Promise<void> {
  try {
    const [rt, analysis] = await Promise.all([getRuntimeMetrics(), getRoutingAnalysis()])
    if (rt.data) metrics.value = rt.data
    if (analysis.data) models.value = analysis.data
    lastUpdated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch {
    // 轮询失败保留上次快照（依赖宕机时后端会降级标注而非断流）
  } finally {
    loading.value = false
  }
}

function fmtDuration(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds))
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m${s % 60}s`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h${m % 60}m`
  return `${Math.floor(h / 24)}d${h % 24}h`
}

function mb(bytes?: number): string {
  if (bytes === undefined || bytes === null) return '-'
  return Math.round(bytes / 1024 / 1024).toString()
}

function statusColor(status?: string): string {
  if (status === 'up') return '#16a34a'
  if (status === 'degraded') return '#d97706'
  return '#dc2626'
}

function modelStatus(m: RoutingAnalysisItem): { color: string; text: string } {
  if (m.blacklisted) return { color: '#dc2626', text: 'CIRCUIT OPEN' }
  if (m.healthScore >= 75) return { color: '#16a34a', text: 'HEALTHY' }
  if (m.healthScore >= 50) return { color: '#d97706', text: 'DEGRADED' }
  return { color: '#dc2626', text: 'CRITICAL' }
}

onMounted(() => {
  void refresh()
  timer = window.setInterval(() => void refresh(), REFRESH_MS)
})

onUnmounted(() => {
  if (timer !== undefined) window.clearInterval(timer)
})
</script>

<style scoped>
.view {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.mon-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.mon-grid {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 14px;
}

.span-12 { grid-column: span 12; }
.span-4 { grid-column: span 4; }

.mon-card {
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: 6px;
  padding: 16px;
}

.mon-card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.head-group {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.mon-card.alert-warn {
  border-color: #d97706;
  box-shadow: 0 0 0 1px rgba(217, 119, 6, 0.35);
}

.mon-card.alert-critical {
  border-color: #dc2626;
  box-shadow: 0 0 0 1px rgba(220, 38, 38, 0.35);
}

.alert-badge {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.04em;
  padding: 2px 8px;
  border-radius: 10px;
}

.alert-badge.warn {
  color: #d97706;
  background: rgba(217, 119, 6, 0.14);
}

.alert-badge.critical {
  color: #dc2626;
  background: rgba(220, 38, 38, 0.14);
}

.live-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.spark {
  display: block;
  width: 100%;
  height: 64px;
}

.spark-axis {
  margin-top: 6px;
  font-size: 11px;
}

.kv {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: var(--color-text-secondary);
  padding: 7px 0;
}

.kv b {
  color: var(--color-text);
}

.kv.dashed {
  border-top: 1px dashed var(--color-border);
}

.bar {
  height: 6px;
  border-radius: 2px;
  background: var(--color-border);
  overflow: hidden;
  margin: 4px 0;
}

.bar i {
  display: block;
  height: 100%;
  border-radius: 2px;
  transition: width 0.4s ease;
}

.hint {
  margin-top: 10px;
  font-size: 11px;
  line-height: 1.5;
}

.empty-note {
  padding: 20px 0;
  text-align: center;
  font-size: 12px;
}

.model-row {
  display: grid;
  grid-template-columns: 12px 120px minmax(0, 1fr) 80px 130px 110px 110px minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  font-size: 12px;
  padding: 10px 0;
}

.model-row + .model-row {
  border-top: 1px dashed var(--color-border);
}

.model-code {
  color: var(--color-text);
  font-weight: 600;
}

.model-name,
.tier,
.reason {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-name { color: var(--color-text); }

@media (max-width: 1100px) {
  .mon-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .span-4 { grid-column: span 12; }
  .model-row {
    grid-template-columns: 12px 120px minmax(0, 1fr) 110px 110px;
  }
  .tier, .reason { display: none; }
}
</style>
