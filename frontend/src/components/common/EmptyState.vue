<template>
    <div class="empty-state">
        <el-icon :size="48" color="var(--color-text-light)">
            <component :is="icon" />
        </el-icon>
        <h3>{{ title }}</h3>
        <p v-if="description">{{ description }}</p>
    </div>
</template>

<script setup lang="ts">
defineProps<{
    icon: string
    title: string
    description?: string
}>()
</script>

<style scoped>
.empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 60px 20px;
    text-align: center;
}

.empty-state h3 {
    font-size: 16px;
    font-weight: 600;
    color: var(--color-text-secondary);
    margin-top: 16px;
}

.empty-state p {
    font-size: 13px;
    color: var(--color-text-light);
    margin-top: 6px;
}
</style>