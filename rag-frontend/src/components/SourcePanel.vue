<template>
  <div class="source-panel" :data-index="index">
    <div class="source-head" @click="expanded = !expanded">
      <span class="cite-badge">[S{{ index + 1 }}]</span>
      <span class="kb">{{ source.kbName || '未知知识库' }}</span>
      <span class="file">{{ source.fileName || '未知文件' }}</span>
      <span v-if="hasChunk" class="chunk">第 {{ Number(source.chunkIndex) + 1 }} 段</span>
      <span v-if="source.score !== null && source.score !== undefined" class="score">
        {{ Math.round(source.score * 100) }}%
      </span>
      <span class="toggle">{{ expanded ? '收起' : '原文' }}</span>
    </div>
    <div v-if="expanded" class="source-body">
      <div class="snippet">{{ source.snippet || '（该来源无预览片段）' }}</div>
      <div class="loc">
        <span v-if="source.pageNo">第 {{ source.pageNo }} 页</span>
        <span v-if="hasRange">字符 {{ source.charStart }}–{{ source.charEnd }}</span>
        <span v-if="hasChunk">chunk #{{ source.chunkIndex }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  source: { type: Object, required: true },
  index: { type: Number, default: 0 }
})

const expanded = ref(false)
const hasChunk = computed(() => props.source.chunkIndex !== null && props.source.chunkIndex !== undefined)
const hasRange = computed(() => props.source.charStart !== null && props.source.charStart !== undefined
  && props.source.charEnd !== null && props.source.charEnd !== undefined)
</script>

<style scoped>
.source-panel {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  background: #fafafa;
  font-size: 12px;
  overflow: hidden;
}
.source-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  cursor: pointer;
  line-height: 1.5;
}
.cite-badge { color: #409eff; font-weight: 600; }
.kb { color: #606266; }
.file { color: #909399; }
.chunk { color: #c0c4cc; }
.score {
  margin-left: auto;
  color: #909399;
  background: rgba(0, 0, 0, 0.04);
  padding: 0 6px;
  border-radius: 3px;
}
.toggle { color: #409eff; }
.source-body { padding: 0 8px 8px; border-top: 1px dashed #ebeef5; }
.snippet { color: #303133; line-height: 1.6; margin-top: 6px; word-break: break-word; }
.loc { display: flex; gap: 10px; color: #909399; margin-top: 4px; flex-wrap: wrap; }
</style>

<style>
.citation-ref { color: #409eff; cursor: pointer; font-weight: 600; }
.source-panel.cite-flash {
  outline: 2px solid #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.15);
}
</style>
