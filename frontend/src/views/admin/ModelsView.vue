<template>
    <div class="page-container">
        <PageHeader title="模型管理" subtitle="管理上游模型接入：端点、密钥、降级链与基准参数">
            <template #action>
                <el-button type="primary" @click="openCreateDialog">
                    <el-icon>
                        <Plus />
                    </el-icon>
                    添加模型
                </el-button>
            </template>
        </PageHeader>

        <el-card shadow="never" class="table-card">
            <el-table :data="models" v-loading="loading" stripe>
                <el-table-column prop="id" label="ID" width="60" />
                <el-table-column prop="modelName" label="显示名称" min-width="130" />
                <el-table-column prop="modelCode" label="模型编码" min-width="130">
                    <template #default="{ row }">
                        <code class="mono">{{ row.modelCode }}</code>
                    </template>
                </el-table-column>
                <el-table-column label="模型池" width="100" align="center">
                    <template #default="{ row }">
                        <el-tag :type="tierTag(row.modelTier).type" size="small" effect="plain">
                            {{ tierTag(row.modelTier).label }}
                        </el-tag>
                    </template>
                </el-table-column>
                <el-table-column prop="endpoint" label="上游端点" min-width="200" show-overflow-tooltip />
                <el-table-column prop="fallbackCode" label="降级链" width="130">
                    <template #default="{ row }">
                        <span v-if="row.fallbackCode" class="mono">{{ row.fallbackCode }}</span>
                        <span v-else class="muted">-</span>
                    </template>
                </el-table-column>
                <el-table-column prop="baselineQuality" label="基准质量" width="90" align="right" />
                <el-table-column prop="baselineLatencyMs" label="基准延迟" width="90" align="right">
                    <template #default="{ row }">{{ row.baselineLatencyMs }}ms</template>
                </el-table-column>
                <el-table-column prop="status" label="状态" width="80" align="center">
                    <template #default="{ row }">
                        <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
                            {{ row.status === 1 ? '启用' : '禁用' }}
                        </el-tag>
                    </template>
                </el-table-column>
                <el-table-column label="操作" width="260" fixed="right">
                    <template #default="{ row }">
                        <el-button text type="primary" size="small" @click="openPrices(row)">价格</el-button>
                        <el-button text type="primary" size="small" @click="openEditDialog(row)">编辑</el-button>
                        <el-popconfirm :title="row.status === 1 ? '确定禁用该模型？禁用后不再参与路由' : '确定启用该模型？'"
                            @confirm="toggleStatus(row)">
                            <template #reference>
                                <el-button text :type="row.status === 1 ? 'danger' : 'success'" size="small">
                                    {{ row.status === 1 ? '禁用' : '启用' }}
                                </el-button>
                            </template>
                        </el-popconfirm>
                        <el-popconfirm title="确定删除该模型？已产生的账单与日志将保留，此操作不可恢复" @confirm="handleDelete(row)">
                            <template #reference>
                                <el-button text type="danger" size="small">删除</el-button>
                            </template>
                        </el-popconfirm>
                    </template>
                </el-table-column>
            </el-table>
        </el-card>

        <!-- 新增 / 编辑模型 -->
        <el-dialog v-model="showDialog" :title="editingId != null ? '编辑模型' : '添加模型'" width="560px"
            :close-on-click-modal="false">
            <el-form :model="form" label-position="top">
                <el-row :gutter="16">
                    <el-col :span="12">
                        <el-form-item label="模型编码 *" required>
                            <el-input v-model="form.modelCode" placeholder="如: glm-4-flash"
                                :disabled="editingId != null" />
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="显示名称 *" required>
                            <el-input v-model="form.modelName" placeholder="如: GLM-4-Flash" />
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item label="上游端点（OpenAI 兼容 /chat/completions）*" required>
                    <el-input v-model="form.endpoint" placeholder="https://open.bigmodel.cn/api/paas/v4/chat/completions" />
                </el-form-item>
                <el-form-item label="上游 API Key" required>
                    <el-input v-model="form.apiKey" type="password" show-password
                        :placeholder="editingId != null ? '留空 = 保持原 Key 不变' : 'sk-...'" />
                </el-form-item>
                <el-row :gutter="16">
                    <el-col :span="12">
                        <el-form-item label="模型池 *">
                            <el-select v-model="form.modelTier" style="width: 100%">
                                <el-option label="便宜池（cheap）" value="cheap" />
                                <el-option label="强模型池（strong）" value="strong" />
                                <el-option label="全池通用（universal）" value="universal" />
                            </el-select>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="降级模型">
                            <el-select v-model="form.fallbackCode" clearable placeholder="主模型熔断时降级到此模型"
                                style="width: 100%">
                                <el-option v-for="m in fallbackOptions" :key="m.modelCode" :label="m.modelName"
                                    :value="m.modelCode" />
                            </el-select>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-row :gutter="16">
                    <el-col :span="12">
                        <el-form-item label="冷启动基准质量分（0-100）">
                            <el-input-number v-model="form.baselineQuality" :min="0" :max="100" style="width: 100%" />
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="冷启动基准延迟（ms）">
                            <el-input-number v-model="form.baselineLatencyMs" :min="0" :max="600000" :step="100"
                                style="width: 100%" />
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item v-if="editingId != null" label="状态">
                    <el-switch v-model="form.status" :active-value="1" :inactive-value="0"
                        active-text="启用" inactive-text="禁用" />
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="showDialog = false">取消</el-button>
                <el-button type="primary" :loading="saving" @click="saveModel">
                    {{ editingId != null ? '保存' : '创建' }}
                </el-button>
            </template>
        </el-dialog>

        <!-- 版本化价格抽屉 -->
        <el-drawer v-model="pricesVisible" :title="`价格版本 —— ${priceModel?.modelName ?? ''}`" size="46%">
            <div class="price-layout">
                <el-card shadow="never" class="new-price-card">
                    <template #header><span class="card-title-sm">新增价格版本（version+1，立即生效）</span></template>
                    <el-form :model="priceForm" label-position="top">
                        <el-row :gutter="12">
                            <el-col :span="12">
                                <el-form-item label="输入单价（元/千Token）">
                                    <el-input-number v-model="priceForm.priceInput" :min="0" :precision="6" :step="0.001"
                                        style="width: 100%" />
                                </el-form-item>
                            </el-col>
                            <el-col :span="12">
                                <el-form-item label="输出单价（元/千Token）">
                                    <el-input-number v-model="priceForm.priceOutput" :min="0" :precision="6" :step="0.001"
                                        style="width: 100%" />
                                </el-form-item>
                            </el-col>
                        </el-row>
                        <el-button type="primary" :loading="priceSaving" @click="addPrice">立即生效新版本</el-button>
                    </el-form>
                </el-card>
                <el-table :data="prices" v-loading="pricesLoading" stripe size="small">
                    <el-table-column prop="version" label="版本" width="70" align="center">
                        <template #default="{ row }">
                            <el-tag v-if="row.status === 1" type="success" size="small">v{{ row.version }} 生效中</el-tag>
                            <el-tag v-else type="info" size="small" effect="plain">v{{ row.version }}</el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column prop="priceInput" label="输入单价" align="right">
                        <template #default="{ row }">¥{{ Number(row.priceInput).toFixed(4) }}</template>
                    </el-table-column>
                    <el-table-column prop="priceOutput" label="输出单价" align="right">
                        <template #default="{ row }">¥{{ Number(row.priceOutput).toFixed(4) }}</template>
                    </el-table-column>
                    <el-table-column label="生效时间" min-width="150">
                        <template #default="{ row }">{{ formatUtcTime(row.effectiveTime) }}</template>
                    </el-table-column>
                </el-table>
            </div>
        </el-drawer>
    </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import { getAdminModels, createModel, updateModel, deleteModel, getModelPrices, addModelPrice } from '@/api/admin'
import type { ModelInfo, ModelUpsertRequest, ModelPrice } from '@/api/types'
import { formatUtcTime } from '@/utils/format'

const models = ref<ModelInfo[]>([])
const loading = ref(false)

const saving = ref(false)
const showDialog = ref(false)
const editingId = ref<number | null>(null)

const defaultForm = (): ModelUpsertRequest => ({
    modelCode: '',
    modelName: '',
    endpoint: '',
    apiKey: '',
    modelTier: 'universal',
    fallbackCode: '',
    baselineQuality: 60,
    baselineLatencyMs: 1500,
    status: 1,
})

const form = reactive<ModelUpsertRequest>(defaultForm())

// 降级链候选：除当前模型外的其他模型
const fallbackOptions = computed(() =>
    models.value.filter(m => m.id !== editingId.value)
)

function tierTag(tier: string): { type: 'info' | 'danger' | 'primary'; label: string } {
    switch (tier) {
        case 'cheap': return { type: 'info', label: '便宜池' }
        case 'strong': return { type: 'danger', label: '强模型' }
        default: return { type: 'primary', label: '全池通用' }
    }
}

async function refresh() {
    loading.value = true
    try {
        const resp = await getAdminModels()
        models.value = resp.data ?? []
    } catch { /* ignore */ } finally {
        loading.value = false
    }
}

function openCreateDialog() {
    editingId.value = null
    Object.assign(form, defaultForm())
    showDialog.value = true
}

function openEditDialog(row: ModelInfo) {
    editingId.value = row.id
    Object.assign(form, {
        modelCode: row.modelCode,
        modelName: row.modelName,
        endpoint: row.endpoint,
        // 编辑时留空 = 保持原 Key（后端不覆盖）
        apiKey: '',
        modelTier: row.modelTier,
        fallbackCode: row.fallbackCode ?? '',
        baselineQuality: row.baselineQuality,
        baselineLatencyMs: row.baselineLatencyMs,
        status: row.status,
    })
    showDialog.value = true
}

async function saveModel() {
    if (!form.modelCode?.trim() || !form.modelName?.trim() || !form.endpoint?.trim()) {
        ElMessage.warning('请填写模型编码、显示名称和上游端点')
        return
    }
    if (editingId.value == null && !form.apiKey?.trim()) {
        ElMessage.warning('新增模型必须填写上游 API Key')
        return
    }
    saving.value = true
    try {
        if (editingId.value != null) {
            await updateModel(editingId.value, form)
            ElMessage.success('保存成功')
        } else {
            await createModel(form)
            ElMessage.success('创建成功')
        }
        showDialog.value = false
        refresh()
    } catch { /* ignore */ } finally {
        saving.value = false
    }
}

/** 启停切换：v2 无独立状态接口，走全量 PUT（apiKey 留空 = 保持原值） */
async function toggleStatus(row: ModelInfo) {
    try {
        const req: ModelUpsertRequest = {
            modelCode: row.modelCode,
            modelName: row.modelName,
            endpoint: row.endpoint,
            apiKey: '',
            modelTier: row.modelTier,
            fallbackCode: row.fallbackCode ?? '',
            baselineQuality: row.baselineQuality,
            baselineLatencyMs: row.baselineLatencyMs,
            status: row.status === 1 ? 0 : 1,
        }
        await updateModel(row.id, req)
        ElMessage.success(row.status === 1 ? '已禁用' : '已启用')
        refresh()
    } catch { /* ignore */ }
}

async function handleDelete(row: ModelInfo) {
    try {
        await deleteModel(row.id)
        ElMessage.success('删除成功')
        refresh()
    } catch { /* ignore */ }
}

// ==================== 价格版本 ====================
const pricesVisible = ref(false)
const pricesLoading = ref(false)
const priceSaving = ref(false)
const priceModel = ref<ModelInfo | null>(null)
const prices = ref<ModelPrice[]>([])
const priceForm = reactive({ priceInput: 0, priceOutput: 0 })

async function openPrices(row: ModelInfo) {
    priceModel.value = row
    pricesVisible.value = true
    await loadPrices(row.id)
    // 新版本默认沿用当前价
    const current = prices.value.find(p => p.status === 1)
    priceForm.priceInput = current ? Number(current.priceInput) : 0
    priceForm.priceOutput = current ? Number(current.priceOutput) : 0
}

async function loadPrices(id: number) {
    pricesLoading.value = true
    try {
        const resp = await getModelPrices(id)
        prices.value = (resp.data ?? []).sort((a, b) => b.version - a.version)
    } catch { /* ignore */ } finally {
        pricesLoading.value = false
    }
}

async function addPrice() {
    if (!priceModel.value) return
    priceSaving.value = true
    try {
        await addModelPrice(priceModel.value.id, {
            priceInput: priceForm.priceInput,
            priceOutput: priceForm.priceOutput,
        })
        ElMessage.success('新价格版本已生效')
        await loadPrices(priceModel.value.id)
    } catch { /* ignore */ } finally {
        priceSaving.value = false
    }
}

onMounted(refresh)
</script>

<style scoped>
.mono {
    font-family: var(--font-mono);
    font-size: 12px;
}

.muted {
    color: var(--color-text-light);
}

.price-layout {
    display: flex;
    flex-direction: column;
    gap: 16px;
}

.new-price-card {
    flex-shrink: 0;
}

.card-title-sm {
    font-weight: 600;
    font-size: 14px;
}
</style>
