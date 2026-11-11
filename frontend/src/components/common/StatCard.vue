<template>
    <div class="stat-card" :style="{ borderLeftColor: color }">
        <div class="stat-head">
            <div class="stat-head-left">
                <span class="stat-dot" :style="{ background: color }"></span>
                <span class="stat-label">{{ label }}</span>
            </div>
            <div v-if="trend !== undefined && trend !== 0" class="stat-trend mono"
                :class="{ up: (trend ?? 0) > 0, down: (trend ?? 0) < 0 }">
                <el-icon :size="12">
                    <component :is="(trend ?? 0) > 0 ? 'CaretTop' : 'CaretBottom'" />
                </el-icon>
                {{ Math.abs(trend ?? 0) }}%
            </div>
        </div>
        <div class="stat-row">
            <span class="stat-value mono">{{ value }}</span>
            <el-icon class="stat-icon" :size="17" :color="color">
                <component :is="icon" />
            </el-icon>
        </div>
        <div v-if="showProgress" class="stat-progress">
            <div class="stat-progress-track">
                <div class="stat-progress-bar" :class="{ danger: pct! >= 90, warn: pct! >= 80 && pct! < 90 }"
                    :style="{ width: (pct ?? 0) + '%', background: color }"></div>
            </div>
            <span class="stat-progress-text mono">{{ pct ?? 0 }}% / {{ subText }}</span>
        </div>
    </div>
</template>

<script setup lang="ts">
defineProps<{
    label: string
    value: string | number
    icon: string
    color: string
    trend?: number
    showProgress?: boolean
    pct?: number
    subText?: string
}>()
</script>

<style scoped>
.stat-card {
    background: var(--color-card);
    border: 1px solid var(--color-border);
    border-left: 2px solid;
    border-radius: var(--radius);
    padding: 14px 16px 12px;
    display: flex;
    flex-direction: column;
    gap: 7px;
    transition: box-shadow 0.15s, border-color 0.15s;
}

.stat-card:hover {
    box-shadow: var(--shadow);
    border-color: #cfd8df;
}

.stat-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    min-height: 16px;
}

.stat-head-left {
    display: flex;
    align-items: center;
    gap: 7px;
    min-width: 0;
}

.stat-dot {
    width: 7px;
    height: 7px;
    border-radius: 2px;
    flex-shrink: 0;
}

.stat-label {
    font-size: 12px;
    color: var(--color-text-secondary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}

.stat-row {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 8px;
}

.stat-value {
    font-size: 21px;
    font-weight: 600;
    line-height: 1.2;
    color: var(--color-text);
    letter-spacing: -0.01em;
}

.stat-icon {
    flex-shrink: 0;
    opacity: 0.75;
}

.stat-progress {
    margin-top: 1px;
}

.stat-progress-track {
    height: 4px;
    background: var(--color-border);
    border-radius: 2px;
    overflow: hidden;
}

.stat-progress-bar {
    height: 100%;
    border-radius: 2px;
    transition: width 0.3s;
}

.stat-progress-bar.warn {
    background: var(--color-warning) !important;
}

.stat-progress-bar.danger {
    background: var(--color-danger) !important;
}

.stat-progress-text {
    display: block;
    font-size: 10.5px;
    color: var(--color-text-light);
    margin-top: 4px;
}

.stat-trend {
    font-size: 11px;
    font-weight: 600;
    display: flex;
    align-items: center;
    gap: 1px;
    flex-shrink: 0;
}

.stat-trend.up {
    color: var(--color-success);
}

.stat-trend.down {
    color: var(--color-danger);
}
</style>
