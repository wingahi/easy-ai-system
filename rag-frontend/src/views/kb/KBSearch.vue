<template>
  <div class="page-container">
    <div class="page-header">
      <div style="display:flex;gap:12px;align-items:center">
        <el-button :icon="ArrowLeft" circle @click="$router.back()" />
        <h2>{{ kb?.name }} - 搜索测试</h2>
      </div>
    </div>

    <div class="card" style="margin-bottom:16px">
      <div style="display:flex;gap:12px">
        <el-input v-model="query" placeholder="输入问题..." size="large" @keyup.enter="doSearch" />
        <el-button type="primary" size="large" :icon="Search" :loading="loading" @click="doSearch">搜索</el-button>
      </div>
    </div>

    <div v-if="result" class="card">
      <el-descriptions title="检索指标" :column="4" border style="margin-bottom:20px">
        <el-descriptions-item label="召回数">{{ result.totalMatches }}</el-descriptions-item>
        <el-descriptions-item label="过滤后">{{ result.filteredMatches }}</el-descriptions-item>
        <el-descriptions-item label="召回率">{{ (result.recallRate * 100).toFixed(1) }}%</el-descriptions-item>
        <el-descriptions-item label="命中率">{{ (result.hitRate * 100).toFixed(1) }}%</el-descriptions-item>
      </el-descriptions>

      <h3 style="margin:0 0 12px">搜索结果</h3>
      <el-empty v-if="!result.results?.length" description="未找到相关内容" />
      <div v-else>
        <div v-for="(item, idx) in result.results" :key="idx" class="result-item">
          <div class="result-header">
            <el-tag size="small" :type="item.hit ? 'success' : 'info'">
              {{ item.hit ? '命中' : '相关' }}
            </el-tag>
            <span class="score">相似度: {{ (item.score * 100).toFixed(1) }}%</span>
          </div>
          <div class="result-content">{{ item.content }}</div>
          <div class="result-source">
            <el-icon><Document /></el-icon>
            来源: {{ item.kbName }} / {{ item.fileName }} 
            <span style="color:#c0c4cc">· 段落 #{{ item.chunkIndex }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft, Search, Document } from '@element-plus/icons-vue'
import { kbGet, kbSearch } from '../../api'

const route = useRoute()
const kbId = route.params.id
const kb = ref(null)
const query = ref('')
const result = ref(null)
const loading = ref(false)

const doSearch = async () => {
  if (!query.value.trim()) return
  loading.value = true
  try {
    const res = await kbSearch(kbId, query.value)
    result.value = res.data
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  const res = await kbGet(kbId)
  kb.value = res.data
})
</script>

<style scoped>
.result-item {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 12px;
  transition: box-shadow 0.2s;
  &:hover { box-shadow: 0 2px 12px rgba(0,0,0,0.08); }
}
.result-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  .score { color: #409eff; font-size: 13px; }
}
.result-content {
  font-size: 14px;
  line-height: 1.7;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-all;
  margin-bottom: 8px;
}
.result-source {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #606266;
}
</style>
