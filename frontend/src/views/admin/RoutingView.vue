<template>
    <div class="page-container">
        <PageHeader title="智能路由" subtitle="多目标打分选型：质量 × 成本，熔断降级实时感知" />

        <!-- 配置 + 实时分析 -->
        <el-row :gutter="20" class="section-row">
            <el-col :xs="24" :md="8">
                <el-card shadow="never" class="table-card config-card">
                    <template #header><span class="card-title">路由配置</span></template>
                    <div class="config-body">
                        <div class="config-item">
                            <div class="config-label">
                                <span>路由总开关</span>
                                <span class="config-desc">关闭后所有请求直连缺省模型</span>
                            </div>
                            <el-switch v-model="enabled" :loading="switching" :before-change="onBeforeSwitch" />
                        </div>
                        <el-divider />
                        <div class="config-item column">
                            <div class="config-label">
                                <span>打分权重（质量 {{ qualityPct }}% / 成本 {{ costPct }}%）</span>
                                <span class="config-desc">score = w_q × 质量分 + w_c × 成本分，拖动后需保存</span>
                            </div>
                            <div class="weight-slider">
                                <span class="slider-tag quality">质量</span>
                                <el-slider v-model="wq" :min="0" :max="1" :step="0.05" :format-tooltip="pctTip" />
                                <span class="slider-tag cost">成本</span>
                            </div>
                            <el-button type="primary" size="small" :loading="weightSaving" :disabled="!weightsDirty"
                                @click="saveWeights">
                                {{ weightsDirty ? '保存权重' : '已与线上一致' }}
                            </el-button>
                        </div>
                        <el-divider />
                        <div class="config-item">
                            <span class="config-label">最近更新</span>
                            <span class="config-time">{{ formatUtcTime(config?.gmtModified) }}</span>
                        </div>
                    </div>
                </el-card>
            </el-col>
            <el-col :xs="24" :md="16">
                <el-card shadow="never" class="table-card">
                    <template #header>
                        <div class="card-header">
                            <span class="card-title">逐模型实时分析</span>
                            <el-button text type="primary" size="small" @click="refreshAll">
                                <el-icon :size="14"><Refresh /></el-icon>
                                刷新
                            </el-button>
                        </div>
                    </template>
                    <el-table :data="analysis" v-loading="analysisLoading" stripe size="small">
                        <el-table-column prop="modelName" label="模型" min-width="120" />
                        <el-table-column prop="modelCode" label="编码" min-width="120">
                            <template #default="{ row }">
                                <code class="mono">{{ row.modelCode }}</code>
                            </template>
                        </el-table-column>
                        <el-table-column label="池" width="86" align="center">
                            <template #default="{ row }">
                                <el-tag size="small" effect="plain" :type="row.modelTier === 'strong' ? 'danger' : (row.modelTier === 'cheap' ? 'info' : 'primary')">
                                    {{ row.modelTier }}
                                </el-tag>
                            </template>
                        </el-table-column>
                        <el-table-column label="质量分" width="110" align="center">
                            <template #default="{ row }">
                                <span v-if="row.avgQuality != null">{{ Number(row.avgQuality).toFixed(1) }}</span>
                                <span v-else class="muted">基准 {{ row.baselineQuality }}</span>
                            </template>
                        </el-table-column>
                        <el-table-column label="P90 延迟" width="100" align="right">
                            <template #default="{ row }">
                                <span v-if="row.p90LatencyMs != null">{{ row.p90LatencyMs }}ms</span>
                                <span v-else class="muted">基准 {{ row.baselineLatencyMs }}ms</span>
                            </template>
                        </el-table-column>
                        <el-table-column label="健康分" width="140" align="center">
                            <template #default="{ row }">
                                <el-progress :percentage="Math.round(row.healthScore)" :stroke-width="10"
                                    :color="healthColor(row.healthScore)" />
                            </template>
                        </el-table-column>
                        <el-table-column label="状态" width="90" align="center">
                            <template #default="{ row }">
                                <el-tag v-if="row.blacklisted" type="danger" size="small">熔断中</el-tag>
                                <el-tag v-else type="success" size="small" effect="plain">健康</el-tag>
                            </template>
                        </el-table-column>
                        <el-table-column label="单价(入/出)" width="130" align="right">
                            <template #default="{ row }">
                                <span v-if="row.priceInput != null">¥{{ Number(row.priceInput).toFixed(4) }} / ¥{{ Number(row.priceOutput ?? 0).toFixed(4) }}</span>
                                <span v-else class="muted">未定价</span>
                            </template>
                        </el-table-column>
                        <el-table-column label="样本" width="60" align="right">
                            <template #default="{ row }">{{ row.qualitySamples }}</template>
                        </el-table-column>
                    </el-table>
                </el-card>
            </el-col>
        </el-row>

        <!-- 决策日志 -->
        <el-card shadow="never" class="table-card">
            <template #header><span class="card-title">智能路由决策日志（点击行首展开决策详情）</span></template>
            <el-table :data="decisions" v-loading="decisionsLoading" stripe>
                <el-table-column type="expand">
                    <template #default="{ row }">
                        <div class="expand-body">
                            <div class="expand-meta">
                                <span>Trace: <code class="mono">{{ row.traceId }}</code></span>
                                <span v-if="row.agentTaskId">Agent 任务: <code class="mono">{{ row.agentTaskId }}</code></span>
                                <span>价格版本: v{{ row.priceVersion }}</span>
                                <span v-if="row.payloadRef">Payload: <code class="mono">{{ row.payloadRef }}</code></span>
                            </div>
                            <pre class="decision-json">{{ pretty(row.routingDecision) }}</pre>
                        </div>
                    </template>
                </el-table-column>
                <el-table-column prop="gmtCreate" label="时间" min-width="150">
                    <template #default="{ row }">{{ formatUtcTime(row.gmtCreate) }}</template>
                </el-table-column>
                <el-table-column prop="userId" label="用户ID" width="80" align="right" />
                <el-table-column prop="modelCode" label="选中模型" min-width="130">
                    <template #default="{ row }">
                        <code class="mono">{{ row.modelCode }}</code>
                    </template>
                </el-table-column>
                <el-table-column label="决策模式" width="100" align="center">
                    <template #default="{ row }">
                        <el-tag :type="modeOf(row.routingDecision) === 'auto' ? 'warning' : 'info'" size="small" effect="plain">
                            {{ modeOf(row.routingDecision) === 'auto' ? '智能路由' : '指定' }}
                        </el-tag>
                    </template>
                </el-table-column>
                <el-table-column prop="totalTokens" label="Tokens" width="90" align="right" />
                <el-table-column prop="costAmount" label="费用" width="90" align="right">
                    <template #default="{ row }">¥{{ Number(row.costAmount ?? 0).toFixed(4) }}</template>
                </el-table-column>
                <el-table-column prop="latencyMs" label="耗时(ms)" width="90" align="right" />
                <el-table-column label="降级" width="70" align="center">
                    <template #default="{ row }">
                        <el-tag v-if="row.fallbackUsed === 1" type="warning" size="small" effect="plain">是</el-tag>
                        <span v-else class="muted">-</span>
                    </template>
                </el-table-column>
            </el-table>
            <div class="pagination-wrapper">
                <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
                    :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next"
                    @current-change="loadDecisions" @size-change="loadDecisions" />
            </div>
        </el-card>
    </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import {
    getRoutingConfig, updateRoutingSwitch, updateRoutingWeights,
    getRoutingAnalysis, getRoutingDecisions,
} from '@/api/admin'
import type { RoutingConfig, RoutingAnalysisItem, InvokeLog } from '@/api/types'
import { formatUtcTime } from '@/utils/format'

// ==================== 配置 ====================
const config = ref<RoutingConfig | null>(null)
const enabled = ref(false)
const wq = ref(0.7)
const switching = ref(false)
const weightSaving = ref(false)

const qualityPct = computed(() => Math.round(wq.value * 100))
const costPct = computed(() => 100 - qualityPct.value)
// 滑杆值与线上值是否不一致
const weightsDirty = computed(() => config.value != null && Math.abs(wq.value - config.value.weightQuality) > 0.001)

function pctTip(v: number): string {
    return `质量 ${Math.round(v * 100)}% / 成本 ${Math.round((1 - v) * 100)}%`
}

async function loadConfig() {
    const resp = await getRoutingConfig().catch(() => null)
    config.value = resp?.data ?? null
    if (config.value) {
        enabled.value = config.value.enabled === 1
        if (!weightsDirty.value) {
            wq.value = config.value.weightQuality
        }
    }
}

/** 开关切换前二次确认 */
async function onBeforeSwitch(): Promise<boolean> {
    const on = !enabled.value
    try {
        await ElMessageBox.confirm(
            on ? '开启后所有请求将按质量×成本打分自动选型，确定开启？' : '关闭后所有请求直连缺省模型，确定关闭？',
            '路由总开关',
            { confirmButtonText: '确定', cancelButtonText: '取消', type: on ? 'info' : 'warning' },
        )
    } catch {
        return false
    }
    switching.value = true
    try {
        await updateRoutingSwitch(on ? 1 : 0)
        ElMessage.success(on ? '智能路由已开启' : '智能路由已关闭')
        await Promise.all([loadConfig(), loadAnalysis()])
        return true
    } catch {
        return false
    } finally {
        switching.value = false
    }
}

async function saveWeights() {
    weightSaving.value = true
    try {
        await updateRoutingWeights(Number(wq.value.toFixed(2)), Number((1 - wq.value).toFixed(2)))
        ElMessage.success('权重已热更新')
        await loadConfig()
    } catch { /* ignore */ } finally {
        weightSaving.value = false
    }
}

// ==================== 实时分析 ====================
const analysis = ref<RoutingAnalysisItem[]>([])
const analysisLoading = ref(false)

function healthColor(score: number): string {
    if (score >= 75) return '#16a34a'
    if (score >= 50) return '#d97706'
    return '#dc2626'
}

async function loadAnalysis() {
    analysisLoading.value = true
    try {
        const resp = await getRoutingAnalysis()
        analysis.value = resp.data ?? []
    } catch { /* ignore */ } finally {
        analysisLoading.value = false
    }
}

// ==================== 决策日志 ====================
const decisions = ref<InvokeLog[]>([])
const decisionsLoading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)

async function loadDecisions() {
    decisionsLoading.value = true
    try {
        const resp = await getRoutingDecisions({ page: page.value, size: size.value })
        decisions.value = resp.data?.records ?? []
        total.value = resp.data?.total ?? 0
    } catch { /* ignore */ } finally {
        decisionsLoading.value = false
    }
}

function pretty(raw: string | null): string {
    if (!raw) return '（本次为指定模型调用，无路由决策）'
    try {
        return JSON.stringify(JSON.parse(raw), null, 2)
    } catch {
        return raw
    }
}

function modeOf(raw: string | null): 'auto' | 'manual' {
    try {
        if (raw && JSON.parse(raw)?.mode) return 'auto'
    } catch { /* ignore */ }
    return 'manual'
}

function refreshAll() {
    loadConfig()
    loadAnalysis()
    loadDecisions()
}

onMounted(refreshAll)
</script>

<style scoped>
.section-row {
    margin-bottom: 20px;
}

.card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.card-title {
    font-weight: 600;
    font-size: 15px;
}

.config-card {
    height: 100%;
}

.config-body {
    display: flex;
    flex-direction: column;
}

.config-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
}

.config-item.column {
    flex-direction: column;
    align-items: stretch;
}

.config-label {
    display: flex;
    flex-direction: column;
    font-size: 14px;
    font-weight: 600;
    color: var(--color-text);
}

.config-desc {
    font-size: 12px;
    font-weight: 400;
    color: var(--color-text-secondary);
    margin-top: 2px;
}

.config-time {
    font-size: 13px;
    color: var(--color-text-secondary);
}

.weight-slider {
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 8px 0 12px;
}

.slider-tag {
    font-size: 12px;
    padding: 2px 8px;
    border-radius: 4px;
    flex-shrink: 0;
}

.slider-tag.quality {
    color: #16a34a;
    background: rgba(22, 163, 74, 0.1);
}

.slider-tag.cost {
    color: #2563eb;
    background: rgba(37, 99, 235, 0.1);
}

.mono {
    font-family: var(--font-mono);
    font-size: 12px;
}

.muted {
    color: var(--color-text-light);
}

.pagination-wrapper {
    display: flex;
    justify-content: flex-end;
}

.expand-body {
    padding: 4px 12px;
}

.expand-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 18px;
    font-size: 13px;
    color: var(--color-text-secondary);
    margin-bottom: 10px;
}

.decision-json {
    margin: 0;
    background: #f7f9fa;
    border: 1px solid var(--color-border);
    border-radius: var(--radius);
    padding: 12px;
    font-family: var(--font-mono);
    font-size: 12px;
    line-height: 1.6;
    max-height: 320px;
    overflow: auto;
}
</style>
