<template>
    <div class="page-header">
        <div>
            <h2>{{ title }}</h2>
            <p v-if="subtitle" class="subtitle">{{ subtitle }}</p>
        </div>
        <slot name="action" />
    </div>
</template>

<script setup lang="ts">
defineProps<{
    title: string
    subtitle?: string
}>()
</script>