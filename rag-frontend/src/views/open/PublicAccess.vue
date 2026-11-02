<template>
  <div class="open-access">
    <el-tabs v-model="activeTab">
      <!-- ===== 分享链接 ===== -->
      <el-tab-pane label="免登录分享" name="share">
        <div class="toolbar">
          <el-input v-model="shareQuery" placeholder="按名称 / token 搜索" clearable style="width: 240px"
                    @keydown.enter="loadShares" @clear="loadShares" />
          <el-button type="primary" :icon="Plus" @click="openShareDialog">创建分享链接</el-button>
          <el-button @click="loadShares">刷新</el-button>
        </div>

        <el-table :data="shares" v-loading="shareLoading" border>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="name" label="名称" min-width="120" />
          <el-table-column prop="scope" label="范围" width="90" />
          <el-table-column prop="targetId" label="目标ID" width="90" />
          <el-table-column label="分享地址" min-width="260">
            <template #default="{ row }">
              <span class="share-url">{{ shareUrl(row.token) }}</span>
              <el-button link type="primary" @click="copy(shareUrl(row.token))">复制</el-button>
            </template>
          </el-table-column>
          <el-table-column label="有效期" width="160">
            <template #default="{ row }">{{ formatTime(row.expiresAt) }}</template>
          </el-table-column>
          <el-table-column label="密码" width="70">
            <template #default="{ row }">{{ row.passwordHash ? '有' : '无' }}</template>
          </el-table-column>
          <el-table-column label="次数" width="90">
            <template #default="{ row }">{{ row.useCount || 0 }} / {{ row.maxUses > 0 ? row.maxUses : '不限' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="shareStatus(row).type" size="small">{{ shareStatus(row).text }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button link type="warning" :disabled="!!row.revoked" @click="revokeShare(row)">吊销</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="pager" background layout="prev, pager, next, total"
                       :total="shareTotal" :page-size="shareSize" v-model:current-page="sharePage"
                       @current-change="loadShares" />
      </el-tab-pane>

      <!-- ===== API Key ===== -->
      <el-tab-pane label="开放 API Key" name="apikey">
        <div class="toolbar">
          <el-input v-model="keyQuery" placeholder="按名称 / 前缀搜索" clearable style="width: 240px"
                    @keydown.enter="loadKeys" @clear="loadKeys" />
          <el-button type="primary" :icon="Plus" @click="openKeyDialog">创建 API Key</el-button>
          <el-button @click="loadKeys">刷新</el-button>
        </div>

        <el-table :data="keys" v-loading="keyLoading" border>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="name" label="名称" min-width="120" />
          <el-table-column prop="keyPrefix" label="前缀" width="130" />
          <el-table-column prop="scopes" label="权限范围" min-width="180" />
          <el-table-column label="过期时间" width="160">
            <template #default="{ row }">{{ formatTime(row.expiresAt) || '长期' }}</template>
          </el-table-column>
          <el-table-column label="最近调用" width="160">
            <template #default="{ row }">{{ formatTime(row.lastUsedTime) || '-' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="row.revoked ? 'info' : 'success'" size="small">
                {{ row.revoked ? '已吊销' : '有效' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="showLogs(row)">调用日志</el-button>
              <el-button link type="warning" :disabled="!!row.revoked" @click="revokeKey(row)">吊销</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="pager" background layout="prev, pager, next, total"
                       :total="keyTotal" :page-size="keySize" v-model:current-page="keyPage"
                       @current-change="loadKeys" />
      </el-tab-pane>
    </el-tabs>

    <!-- 创建分享 -->
    <el-dialog v-model="shareDialog" title="创建分享链接" width="520px">
      <el-form label-width="100px">
        <el-form-item label="名称">
          <el-input v-model="shareForm.name" placeholder="运营备注，如「产品手册问答」" />
        </el-form-item>
        <el-form-item label="访问范围">
          <el-select v-model="shareForm.scope" style="width: 100%">
            <el-option label="聊天窗口（可匿名提问）" value="WINDOW" />
            <el-option label="会话记录（只读）" value="SESSION" />
            <el-option label="知识库（只读检索）" value="KB" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标">
          <el-select v-if="shareForm.scope === 'WINDOW'" v-model="shareForm.targetId" filterable style="width: 100%">
            <el-option v-for="w in windows" :key="w.id" :label="`#${w.id} ${w.name}`" :value="w.id" />
          </el-select>
          <el-select v-else-if="shareForm.scope === 'KB'" v-model="shareForm.targetId" filterable style="width: 100%">
            <el-option v-for="k in kbs" :key="k.id" :label="`#${k.id} ${k.name}`" :value="k.id" />
          </el-select>
          <el-input-number v-else v-model="shareForm.targetId" :min="1" :controls="false" style="width: 100%"
                           placeholder="请输入会话 ID" />
        </el-form-item>
        <el-form-item label="有效期（天）">
          <el-input-number v-model="shareForm.expiresDays" :min="1" :max="maxExpireDays" />
          <span class="form-hint">上限 {{ maxExpireDays }} 天</span>
        </el-form-item>
        <el-form-item label="访问密码">
          <el-input v-model="shareForm.password" placeholder="可选，留空表示免密" show-password />
        </el-form-item>
        <el-form-item label="最大次数">
          <el-input-number v-model="shareForm.maxUses" :min="0" />
          <span class="form-hint">0 表示不限</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="shareDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="createShare">创建</el-button>
      </template>
    </el-dialog>

    <!-- 创建 API Key -->
    <el-dialog v-model="keyDialog" title="创建 API Key" width="480px">
      <el-form label-width="100px">
        <el-form-item label="名称">
          <el-input v-model="keyForm.name" placeholder="如「客服系统集成」" />
        </el-form-item>
        <el-form-item label="权限范围">
          <el-checkbox-group v-model="keyForm.scopes">
            <el-checkbox label="chat:write">对话</el-checkbox>
            <el-checkbox label="kb:read">知识库检索</el-checkbox>
            <el-checkbox label="usage:read">用量查询</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="有效期（天）">
          <el-input-number v-model="keyForm.expiresDays" :min="1" />
          <span class="form-hint">留空/0 表示长期</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="keyDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="createKey">创建</el-button>
      </template>
    </el-dialog>

    <!-- 明文 Key -->
    <el-dialog v-model="plainDialog" title="API Key 已创建" width="560px">
      <el-alert type="warning" :closable="false" show-icon
                title="明文只显示一次，请立即保存；服务端仅存 SHA-256 哈希。" />
      <div class="plain-key">{{ plainKey }}</div>
      <template #footer>
        <el-button type="primary" @click="copy(plainKey)">复制并关闭</el-button>
      </template>
    </el-dialog>

    <!-- 调用日志 -->
    <el-dialog v-model="logsDialog" title="调用日志" width="760px">
      <el-table :data="logs" border max-height="420">
        <el-table-column prop="createTime" label="时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column prop="method" label="方法" width="70" />
        <el-table-column prop="path" label="接口" min-width="220" />
        <el-table-column prop="statusCode" label="状态" width="70" />
        <el-table-column prop="durationMs" label="耗时(ms)" width="90" />
        <el-table-column prop="ip" label="IP" width="140" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  shareLinkPage, shareLinkCreate, shareLinkRevoke,
  apiKeyPage, apiKeyCreate, apiKeyRevoke, apiKeyCallLogs,
  windowPage, kbList
} from '../../api'

const activeTab = ref('share')

// ===== 分享链接 =====
const shares = ref([])
const shareTotal = ref(0)
const sharePage = ref(1)
const shareSize = 10
const shareQuery = ref('')
const shareLoading = ref(false)
const shareDialog = ref(false)
const saving = ref(false)
const maxExpireDays = ref(30)
const windows = ref([])
const kbs = ref([])
const shareForm = ref({ name: '', scope: 'WINDOW', targetId: null, expiresDays: 7, password: '', maxUses: 0 })

const loadShares = async () => {
  shareLoading.value = true
  try {
    const res = await shareLinkPage({ page: sharePage.value, size: shareSize, keyword: shareQuery.value || undefined })
    shares.value = res.data?.records || []
    shareTotal.value = res.data?.total || 0
  } finally {
    shareLoading.value = false
  }
}

const openShareDialog = async () => {
  shareForm.value = { name: '', scope: 'WINDOW', targetId: null, expiresDays: 7, password: '', maxUses: 0 }
  shareDialog.value = true
  if (!windows.value.length) {
    const res = await windowPage({ page: 1, size: 100 })
    windows.value = res.data?.records || []
  }
  if (!kbs.value.length) {
    const res = await kbList()
    kbs.value = res.data || []
  }
}

const createShare = async () => {
  if (!shareForm.value.targetId) { ElMessage.warning('请选择分享目标'); return }
  saving.value = true
  try {
    const res = await shareLinkCreate(shareForm.value)
    ElMessage.success('分享链接已创建')
    shareDialog.value = false
    await loadShares()
    copy(shareUrl(res.data.token))
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  } finally {
    saving.value = false
  }
}

const revokeShare = async (row) => {
  try {
    await ElMessageBox.confirm('吊销后该链接立即失效，确认？', '吊销分享链接', { type: 'warning' })
    await shareLinkRevoke(row.id)
    ElMessage.success('已吊销')
    loadShares()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '吊销失败')
  }
}

const shareUrl = (token) => `${location.origin}/share/${token}`
const isExpired = (row) => row.expiresAt && new Date(row.expiresAt) < new Date()
const shareStatus = (row) => {
  if (row.revoked) return { text: '已吊销', type: 'info' }
  if (isExpired(row)) return { text: '已过期', type: 'danger' }
  if (row.maxUses > 0 && row.useCount >= row.maxUses) return { text: '次数用尽', type: 'warning' }
  return { text: '有效', type: 'success' }
}

// ===== API Key =====
const keys = ref([])
const keyTotal = ref(0)
const keyPage = ref(1)
const keySize = 10
const keyQuery = ref('')
const keyLoading = ref(false)
const keyDialog = ref(false)
const plainDialog = ref(false)
const plainKey = ref('')
const keyForm = ref({ name: '', scopes: ['chat:write'], expiresDays: 0 })
const logsDialog = ref(false)
const logs = ref([])

const loadKeys = async () => {
  keyLoading.value = true
  try {
    const res = await apiKeyPage({ page: keyPage.value, size: keySize, keyword: keyQuery.value || undefined })
    keys.value = res.data?.records || []
    keyTotal.value = res.data?.total || 0
  } finally {
    keyLoading.value = false
  }
}

const openKeyDialog = () => {
  keyForm.value = { name: '', scopes: ['chat:write'], expiresDays: 0 }
  keyDialog.value = true
}

const createKey = async () => {
  if (!keyForm.value.name) { ElMessage.warning('请输入名称'); return }
  if (!keyForm.value.scopes.length) { ElMessage.warning('请至少选择一个权限范围'); return }
  saving.value = true
  try {
    const res = await apiKeyCreate(keyForm.value)
    plainKey.value = res.data.plainKey
    keyDialog.value = false
    plainDialog.value = true
    await loadKeys()
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  } finally {
    saving.value = false
  }
}

const revokeKey = async (row) => {
  try {
    await ElMessageBox.confirm('吊销后该 Key 立即失效，确认？', '吊销 API Key', { type: 'warning' })
    await apiKeyRevoke(row.id)
    ElMessage.success('已吊销')
    loadKeys()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '吊销失败')
  }
}

const showLogs = async (row) => {
  const res = await apiKeyCallLogs(row.id, { limit: 50 })
  logs.value = res.data || []
  logsDialog.value = true
}

// ===== 通用 =====
const copy = async (text) => {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动复制')
  }
}
const formatTime = (t) => {
  if (!t) return ''
  const d = new Date(t)
  if (isNaN(d.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(() => {
  loadShares()
  loadKeys()
})
</script>

<style scoped>
.open-access { padding: 4px; }
.toolbar { display: flex; gap: 12px; margin-bottom: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
.share-url { color: #606266; font-size: 12px; word-break: break-all; margin-right: 6px; }
.form-hint { margin-left: 8px; color: #909399; font-size: 12px; }
.plain-key { margin-top: 12px; padding: 12px; background: #f5f7fa; border-radius: 6px;
  font-family: Menlo, Consolas, monospace; font-size: 13px; word-break: break-all; }
</style>
