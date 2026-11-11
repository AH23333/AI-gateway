<template>
  <div class="markdown-body" ref="mdRef" v-html="rendered" />
</template>

<script setup lang="ts">
import { computed, ref, onMounted, watch, nextTick } from 'vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import 'highlight.js/styles/github-dark.css'

const props = defineProps<{ content: string }>()
const mdRef = ref<HTMLElement>()

const md = new MarkdownIt({
  html: false,
  breaks: true,
  linkify: true,
  highlight(str: string, lang: string) {
    const langClass = lang ? ` data-lang="${lang}"` : ''
    if (lang && hljs.getLanguage(lang)) {
      try {
        const highlighted = hljs.highlight(str, { language: lang, ignoreIllegals: true }).value
        return `<pre class="hljs code-block-wrapper"${langClass}><div class="code-block-header"><span class="code-lang">${lang}</span><button class="code-copy-btn" onclick="(function(btn){var pre=btn.closest('pre');var code=pre.querySelector('code');navigator.clipboard.writeText(code.textContent||'').then(function(){btn.textContent='已复制';setTimeout(function(){btn.textContent='复制'},1500)})})(event.target)">复制</button></div><code>${highlighted}</code></pre>`
      } catch { /* fall through */ }
    }
    return `<pre class="hljs code-block-wrapper"${langClass}><div class="code-block-header"><span class="code-lang">${lang || 'text'}</span><button class="code-copy-btn" onclick="(function(btn){var pre=btn.closest('pre');var code=pre.querySelector('code');navigator.clipboard.writeText(code.textContent||'').then(function(){btn.textContent='已复制';setTimeout(function(){btn.textContent='复制'},1500)})})(event.target)">复制</button></div><code>${md.utils.escapeHtml(str)}</code></pre>`
  },
})

const rendered = computed(() => md.render(props.content))
</script>

<style>
/* markdown 内容样式 */
.markdown-body {
  line-height: 1.75;
  font-size: 14px;
  color: inherit;
}

.markdown-body h1,
.markdown-body h2,
.markdown-body h3,
.markdown-body h4 {
  margin: 20px 0 10px;
  font-weight: 600;
  line-height: 1.4;
}

.markdown-body h1 { font-size: 1.5em; }
.markdown-body h2 { font-size: 1.3em; }
.markdown-body h3 { font-size: 1.15em; }

.markdown-body p {
  margin: 10px 0;
}

.markdown-body ul,
.markdown-body ol {
  padding-left: 22px;
  margin: 10px 0;
}

.markdown-body li {
  margin: 4px 0;
}

.markdown-body code {
  background: rgba(15, 118, 110, 0.08);
  color: var(--color-primary);
  padding: 2px 6px;
  border-radius: 4px;
  font-family: var(--font-mono);
  font-size: 0.9em;
}

/* 代码块容器 */
.markdown-body .code-block-wrapper {
  position: relative;
  margin: 14px 0;
  border-radius: var(--radius-lg);
  overflow: hidden;
  background: #0d1117;
}

.markdown-body .code-block-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 14px;
  background: #161d26;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.markdown-body .code-lang {
  font-size: 11px;
  font-weight: 600;
  color: #5eead4;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  font-family: var(--font-mono);
}

.markdown-body .code-copy-btn {
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.6);
  font-size: 11px;
  padding: 3px 10px;
  border-radius: 5px;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}

.markdown-body .code-copy-btn:hover {
  background: rgba(255, 255, 255, 0.15);
  color: #fff;
}

.markdown-body pre code {
  display: block;
  padding: 14px 16px;
  overflow-x: auto;
  background: transparent;
  color: #d6dee6;
  font-size: 13px;
  line-height: 1.65;
  border-radius: 0;
}

.markdown-body blockquote {
  border-left: 3px solid var(--color-primary);
  padding: 4px 14px;
  margin: 14px 0;
  color: var(--color-text-secondary);
  background: var(--color-primary-10);
  border-radius: 0 6px 6px 0;
}

.markdown-body table {
  border-collapse: collapse;
  width: 100%;
  margin: 14px 0;
  font-size: 13px;
}

.markdown-body th,
.markdown-body td {
  border: 1px solid var(--color-border);
  padding: 10px 14px;
  text-align: left;
}

.markdown-body th {
  background: #f8f9fb;
  font-weight: 600;
}

.markdown-body a {
  color: var(--color-primary);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.markdown-body hr {
  border: none;
  border-top: 1px solid var(--color-border);
  margin: 20px 0;
}

.markdown-body img {
  max-width: 100%;
  border-radius: 8px;
  margin: 10px 0;
}
</style>