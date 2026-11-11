<template>
    <div class="page-container">
        <PageHeader title="系统设置" subtitle="站点标识 / 安全 / 流量防护在线改，保存即生效，无需重启" />

        <el-row :gutter="20" class="section-row">
            <!-- 站点设置 -->
            <el-col :xs="24" :md="12">
                <el-card shadow="never" class="table-card config-card" v-loading="loading">
                    <template #header>
                        <div class="card-header">
                            <span class="card-title">站点设置</span>
                            <span class="card-sub">登录页免认证可见（/settings/public）</span>
                        </div>
                    </template>
                    <div class="config-body">
                        <div v-for="item in siteItems" :key="item.key" class="config-item column">
                            <div class="config-label">
                                <span>{{ item.label }}</span>
                                <span class="config-desc">{{ descriptionOf(item.key) }}</span>
                            </div>
                            <div class="setting-control">
                                <el-input v-if="item.kind === 'textarea'" v-model="textDrafts[item.key]"
                                    type="textarea" :rows="3" maxlength="500" show-word-limit
                                    placeholder="展示在登录页的公告横幅，留空不展示" />
                                <el-input v-else v-model="textDrafts[item.key]" maxlength="50" show-word-limit
                                    placeholder="AI Gateway" />
                                <el-button type="primary" size="small" :disabled="!dirty(item.key)"
                                    :loading="savingKey === item.key" @click="save(item)">
                                    {{ dirty(item.key) ? '保存' : '已与线上一致' }}
                                </el-button>
                            </div>
                            <div class="setting-meta">最近更新：{{ updatedText(item.key) }}</div>
                        </div>
                    </div>
                </el-card>
            </el-col>

            <!-- 安全与流量防护 -->
            <el-col :xs="24" :md="12">
                <el-card shadow="never" class="table-card config-card" v-loading="loading">
                    <template #header>
                        <div class="card-header">
                            <span class="card-title">安全与流量防护</span>
                            <span class="card-sub">JWT 签发与限流阈值实时读取</span>
                        </div>
                    </template>
                    <div class="config-body">
                        <div v-for="item in guardItems" :key="item.key" class="config-item column">
                            <div class="config-label">
                                <span>{{ item.label }}</span>
                                <span class="config-desc">{{ descriptionOf(item.key) }}</span>
                            </div>
                            <div class="setting-control">
                                <div class="number-wrap">
                                    <el-input-number v-model="numDrafts[item.key]" :min="item.min" :max="item.max"
                                        :step="item.step" controls-position="right" class="number-input" />
                                    <span class="unit">{{ item.unit }}</span>
                                </div>
                                <el-button type="primary" size="small" :disabled="!dirty(item.key)"
                                    :loading="savingKey === item.key" @click="save(item)">
                                    {{ dirty(item.key) ? '保存' : '已与线上一致' }}
                                </el-button>
                            </div>
                            <div class="setting-meta">最近更新：{{ updatedText(item.key) }}</div>
                        </div>
                    </div>
                </el-card>
            </el-col>
        </el-row>
    </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import { getSystemSettings, updateSystemSetting } from '@/api/admin'
import type { SystemConfig } from '@/api/types'
import { formatUtcTime } from '@/utils/format'

interface SettingItem {
    key: string
    label: string
    kind: 'text' | 'textarea' | 'number'
    min?: number
    max?: number
    step?: number
    unit?: string
}

const siteItems: SettingItem[] = [
    { key: 'site.name', label: '站点名称', kind: 'text' },
    { key: 'site.announcement', label: '站点公告', kind: 'textarea' },
]

const guardItems: SettingItem[] = [
    { key: 'jwt.access-expire-minutes', label: 'AccessToken 有效期', kind: 'number', min: 5, max: 1440, step: 5, unit: '分钟' },
    { key: 'access.rate-limit', label: '限流阈值', kind: 'number', min: 1, max: 100000, step: 10, unit: '次/窗口' },
    { key: 'access.rate-window-seconds', label: '限流窗口', kind: 'number', min: 10, max: 3600, step: 10, unit: '秒' },
]

const allItems: SettingItem[] = [...siteItems, ...guardItems]

const rows = ref<SystemConfig[]>([])
const textDrafts = reactive<Record<string, string>>({})
const numDrafts = reactive<Record<string, number>>({})
const loading = ref(false)
const savingKey = ref('')

function rowOf(key: string): SystemConfig | undefined {
    return rows.value.find((r) => r.configKey === key)
}

function findItem(key: string): SettingItem | undefined {
    return allItems.find((i) => i.key === key)
}

/** 说明文案取后端（代码定义启动时同步 DB，单一事实源） */
function descriptionOf(key: string): string {
    return rowOf(key)?.description ?? ''
}

function dirty(key: string): boolean {
    const row = rowOf(key)
    const item = findItem(key)
    if (row == null || item == null) return false
    const saved = row.configValue ?? ''
    if (item.kind === 'number') {
        return String(numDrafts[key] ?? '') !== saved
    }
    return (textDrafts[key] ?? '') !== saved
}

function updatedText(key: string): string {
    const row = rowOf(key)
    return row?.gmtModified ? formatUtcTime(row.gmtModified) : '-'
}

async function load() {
    loading.value = true
    try {
        const resp = await getSystemSettings()
        rows.value = resp.data ?? []
        for (const row of rows.value) {
            const item = findItem(row.configKey)
            if (item == null) continue
            if (item.kind === 'number') {
                numDrafts[row.configKey] = Number(row.configValue)
            } else {
                textDrafts[row.configKey] = row.configValue ?? ''
            }
        }
    } catch { /* ignore */ } finally {
        loading.value = false
    }
}

/** 逐键保存：不同键校验规则不同（后端按键归一化），保存成功后重载 */
async function save(item: SettingItem) {
    savingKey.value = item.key
    try {
        const value = item.kind === 'number' ? String(numDrafts[item.key]) : (textDrafts[item.key] ?? '')
        await updateSystemSetting({ key: item.key, value })
        ElMessage.success('已保存，即时生效')
        await load()
    } catch { /* ignore */ } finally {
        savingKey.value = ''
    }
}

onMounted(load)
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

.card-sub {
    font-size: 12px;
    color: var(--color-text-secondary);
}

.config-card {
    height: 100%;
}

.config-body {
    display: flex;
    flex-direction: column;
    gap: 18px;
}

.config-item.column {
    display: flex;
    flex-direction: column;
    gap: 8px;
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

.setting-control {
    display: flex;
    align-items: flex-start;
    gap: 10px;
}

.setting-control .el-textarea,
.setting-control .el-input {
    flex: 1;
}

.setting-control .el-button {
    flex-shrink: 0;
    margin-top: 2px;
}

.number-wrap {
    flex: 1;
    display: flex;
    align-items: center;
    gap: 8px;
}

.number-input {
    width: 180px;
}

.unit {
    font-size: 12px;
    color: var(--color-text-secondary);
}

.setting-meta {
    font-size: 12px;
    color: var(--color-text-light);
}
</style>
