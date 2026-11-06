<template>
  <div class="share-page" :class="{ embed: isEmbed }">
    <div class="share-header">
      <div class="header-inner">
        <el-button v-if="mode === 'chat' && !isEmbed" class="new-session-btn" type="primary" :icon="Plus"
                   @click="newSession">新建会话</el-button>
        <div class="header-titles">
          <h1>{{ title }}</h1>
          <p>{{ subtitle }}</p>
        </div>
        <div class="header-right">
          <el-tag v-if="readOnly" type="info" size="small">只读分享</el-tag>
        </div>
      </div>
    </div>

    <div v-if="loadError" class="error-panel">
      <el-result icon="warning" :title="loadError" sub-title="请联系分享者获取新的链接" />
    </div>

    <div v-else class="share-body">
      <!-- 聊天模式：历史会话 -->
      <aside v-if="mode === 'chat'" class="session-sidebar">
        <div class="session-header">历史会话</div>
        <div class="session-list">
          <div v-for="s in sessions" :key="s.id"
               :class="['session-item', { active: s.id === currentSessionId }]"
               @click="selectSession(s)">
            <div class="session-item-body">
              <div class="session-title">{{ s.title || '新对话' }}</div>
              <div class="session-meta">{{ s.messageCount || 0 }} 条 · {{ formatTime(s.updateTime || s.createTime) }}</div>
            </div>
            <el-popconfirm title="确认删除该会话及其全部消息？" width="220"
                           confirm-button-type="danger" confirm-button-text="删除" cancel-button-text="取消"
                           @confirm="deleteSession(s)">
              <template #reference>
                <el-icon class="session-delete" @click.stop><Delete /></el-icon>
              </template>
            </el-popconfirm>
          </div>
          <div v-if="!sessions.length" class="session-empty">暂无历史会话</div>
        </div>
      </aside>

      <!-- 知识库只读检索 -->
      <div v-if="mode === 'kb'" class="share-chat kb-panel">
        <div class="kb-search-bar">
          <el-input v-model="kbQuery" placeholder="输入问题检索知识库..." @keydown.enter="kbSearch" clearable />
          <el-button type="primary" :loading="kbLoading" @click="kbSearch">检索</el-button>
        </div>
        <div class="kb-results">
          <div v-if="!kbResults.length" class="welcome">
            <h2>📚 {{ title }}</h2>
            <p>这是一个只读知识库分享，输入关键词即可检索相关片段</p>
          </div>
          <div v-for="(r, i) in kbResults" :key="i" class="kb-item">
            <div class="kb-item-head">
              <span class="source-tag">{{ r.kbName || '知识库' }} / {{ r.fileName || '文档' }}</span>
              <span class="score">{{ Number(r.score || 0).toFixed(4) }}</span>
            </div>
            <div class="kb-item-content">{{ r.content }}</div>
          </div>
        </div>
      </div>

      <!-- 聊天 / 只读会话 -->
      <div v-else class="share-chat">
        <div class="messages-container" ref="messagesRef">
          <div v-if="messages.length === 0" class="welcome">
            <h2>👋 你好，我是 {{ title }}</h2>
            <p>{{ readOnly ? '以下是分享的会话记录' : '请输入你的问题，我会基于知识库为你解答' }}</p>
          </div>
          <div v-for="(msg, idx) in messages" :key="idx"
               :class="['msg', msg.role === 'USER' ? 'user' : 'bot']">
            <div class="avatar">{{ msg.role === 'USER' ? '🙂' : '🤖' }}</div>
            <div class="bubble">
              <div v-html="renderMarkdown(msg.content)"></div>
              <div v-if="msg.streaming" class="typing"><span></span><span></span><span></span></div>
              <div v-if="msg.stopped" class="stopped-tip">
                <el-tag size="small" type="info">已停止生成</el-tag>
              </div>
              <div v-if="dedupSources(msg.sources).length" class="sources">
                <span v-for="(s, i) in dedupSources(msg.sources)" :key="i" class="source-tag">
                  📎 {{ s.kbName }}/{{ s.fileName }}
                </span>
              </div>
            </div>
          </div>
        </div>
        <div v-if="!readOnly" class="input-area">
          <el-input v-model="input" type="textarea" :rows="2" placeholder="输入消息..."
                    @keydown.enter.exact.prevent="send" :disabled="streaming" resize="none" />
          <el-button v-if="!streaming" type="primary" :icon="Promotion" @click="send">发送</el-button>
          <el-button v-else type="danger" plain :icon="VideoPause" @click="stop">停止生成</el-button>
        </div>
      </div>
    </div>

    <div class="share-footer">
      <span>Powered by WGH RAG System</span>
    </div>

    <!-- 访问密码 -->
    <el-dialog v-model="passwordDialog" title="该分享需要访问密码" width="360px"
               :close-on-click-modal="false" :show-close="false">
      <el-input v-model="passwordInput" type="password" show-password placeholder="请输入访问密码"
                :disabled="passwordLocked" @keydown.enter="unlock" />
      <div v-if="passwordLocked" class="pwd-lock-hint">
        密码错误次数过多，请 {{ lockRemainingSeconds }} 秒后重试
      </div>
      <template #footer>
        <el-button type="primary" :loading="unlocking" :disabled="passwordLocked" @click="unlock">进入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Promotion, VideoPause, Plus, Delete } from '@element-plus/icons-vue'
import { renderMarkdown } from '../../utils/markdown'
import { stopGeneration } from '../../utils/chatStream'
import {
  publicShareInfo, publicShareUnlock, publicShareSessions, publicShareCreateSession,
  publicShareDeleteSession, publicShareMessages, publicShareKbSearch, streamPublicShareChat,
  setPublicShareEmbedGrant
} from '../../api'

const route = useRoute()
const token = route.params.token
const isEmbed = computed(() => route.query.embed === '1')
const embedGrant = typeof route.query.grant === 'string' ? route.query.grant : null
if (isEmbed.value && embedGrant) {
  setPublicShareEmbedGrant(embedGrant)
}

const info = ref(null)
const loadError = ref('')
const passwordDialog = ref(false)
const passwordInput = ref('')
const password = ref('')
const unlocking = ref(false)
const passwordLocked = computed(() => !!info.value?.passwordLocked)
const lockRemainingSeconds = computed(() => info.value?.lockRemainingSeconds || 0)

const sessions = ref([])
const currentSessionId = ref(null)
const messages = ref([])
const input = ref('')
const streaming = ref(false)
const messagesRef = ref(null)
let streamer = null

const kbQuery = ref('')
const kbResults = ref([])
const kbLoading = ref(false)

const mode = computed(() => info.value?.mode || 'chat')
const readOnly = computed(() => !!info.value?.readOnly)
const title = computed(() => info.value?.title || 'AI 助手')
const subtitle = computed(() => info.value?.description || (readOnly.value ? '只读分享' : '基于知识库的 AI 助手'))

const load = async () => {
  if (isEmbed.value && !embedGrant) {
    loadError.value = '缺少 Widget 嵌入授权，请通过 widget.js 加载分享页'
    return
  }
  try {
    const res = await publicShareInfo(token, password.value || null)
    info.value = res.data
    if (info.value.passwordRequired && !info.value.passwordVerified) {
      passwordDialog.value = true
      if (info.value.passwordLocked) {
        ElMessage.warning(`访问密码错误次数过多，请 ${info.value.lockRemainingSeconds} 秒后重试`)
      }
      return
    }
    await loadContent()
  } catch (e) {
    loadError.value = e.message || '分享链接不可用'
  }
}

const loadContent = async () => {
  if (mode.value === 'chat') {
    await loadSessions()
    await loadHistory()
  } else {
    await loadHistory()
  }
}

const unlock = async () => {
  if (!passwordInput.value) { ElMessage.warning('请输入访问密码'); return }
  unlocking.value = true
  try {
    await publicShareUnlock(token, passwordInput.value)
    password.value = passwordInput.value
    passwordDialog.value = false
    const res = await publicShareInfo(token, password.value)
    info.value = res.data
    await loadContent()
  } catch (e) {
    ElMessage.error(e.message || '密码错误')
  } finally {
    unlocking.value = false
  }
}

const loadSessions = async () => {
  try {
    const res = await publicShareSessions(token, password.value)
    sessions.value = res.data || []
    if (!currentSessionId.value && sessions.value.length) currentSessionId.value = sessions.value[0].id
  } catch (e) {
    console.warn('加载会话失败', e)
  }
}

const loadHistory = async () => {
  try {
    const res = await publicShareMessages(token, {
      sessionId: mode.value === 'chat' ? (currentSessionId.value || undefined) : undefined,
      page: 1, size: 50
    }, password.value)
    messages.value = (res.data?.records || []).map(m => ({
      role: m.role,
      content: m.content,
      stopped: m.status === 'STOPPED',
      sources: m.sources ? safeParseSources(m.sources) : []
    }))
    await nextTick(scroll)
  } catch (e) {
    console.warn('加载历史消息失败', e)
  }
}

const selectSession = async (s) => {
  if (s.id === currentSessionId.value) return
  if (streaming.value) stop()
  currentSessionId.value = s.id
  messages.value = []
  await loadHistory()
}

const newSession = async () => {
  if (streaming.value) stop()
  try {
    const res = await publicShareCreateSession(token, password.value)
    currentSessionId.value = res.data.id
    sessions.value = [res.data, ...sessions.value]
    messages.value = []
  } catch (e) {
    ElMessage.error('新建会话失败: ' + (e.message || ''))
  }
}

const deleteSession = async (s) => {
  try {
    await publicShareDeleteSession(token, s.id, password.value)
    sessions.value = sessions.value.filter(x => x.id !== s.id)
    if (currentSessionId.value === s.id) {
      currentSessionId.value = sessions.value[0]?.id || null
      messages.value = []
      if (currentSessionId.value) await loadHistory()
    }
    ElMessage.success('会话已删除')
  } catch (e) {
    ElMessage.error('删除失败: ' + (e.message || ''))
  }
}

// 停止生成：中断 SSE，保留已生成内容并标记「已停止」
const stop = () => {
  if (!streaming.value) return
  const last = messages.value[messages.value.length - 1]
  stopGeneration(streamer, streaming, last?.role === 'ASSISTANT' ? last : null)
  nextTick(scroll)
}

const send = async () => {
  const text = input.value.trim()
  if (!text || streaming.value) return
  input.value = ''
  messages.value.push({ role: 'USER', content: text })
  const aiMsg = reactive({ role: 'ASSISTANT', content: '', streaming: true, sources: [] })
  messages.value.push(aiMsg)
  streaming.value = true
  await nextTick(scroll)

  streamer = streamPublicShareChat(token, text, currentSessionId.value, password.value)
  streamer.doStream(
    (event, data) => {
      if (event === 'start') {
        if (data?.sessionId) currentSessionId.value = data.sessionId
      } else if (event === 'content') {
        aiMsg.content += (data && typeof data === 'object' ? (data.text ?? '') : data)
      } else if (event === 'sources') {
        aiMsg.sources = data
      } else if (event === 'done') {
        streaming.value = false
        aiMsg.streaming = false
        if (!aiMsg.content && data?.fullResponse) aiMsg.content = data.fullResponse
        if (data?.sessionId) currentSessionId.value = data.sessionId
        loadSessions()
      }
      nextTick(scroll)
    },
    () => { streaming.value = false; aiMsg.streaming = false },
    (err) => {
      streaming.value = false
      aiMsg.streaming = false
      aiMsg.content += '\n⚠️ ' + (err.message || '请求失败')
    }
  )
}

const kbSearch = async () => {
  const q = kbQuery.value.trim()
  if (!q || kbLoading.value) return
  kbLoading.value = true
  try {
    const res = await publicShareKbSearch(token, { query: q, topK: 5 }, password.value)
    kbResults.value = res.data?.results || []
  } catch (e) {
    ElMessage.error('检索失败: ' + (e.message || ''))
  } finally {
    kbLoading.value = false
  }
}

const scroll = () => nextTick(() => {
  if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight
})

const safeParseSources = (json) => { try { return JSON.parse(json) || [] } catch { return [] } }
const dedupSources = (list) => {
  if (!Array.isArray(list)) return []
  const byFile = new Map()
  for (const s of list) {
    const key = s.fileName || s.kbName || JSON.stringify(s)
    const prev = byFile.get(key)
    if (!prev || (s.score ?? 0) > (prev.score ?? 0)) byFile.set(key, s)
  }
  return Array.from(byFile.values())
}
const formatTime = (t) => {
  if (!t) return ''
  const d = new Date(t)
  if (isNaN(d.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const handleBeforeUnload = () => streamer?.abort()
onMounted(() => {
  globalThis.addEventListener('beforeunload', handleBeforeUnload)
  load()
})
onUnmounted(() => {
  globalThis.removeEventListener('beforeunload', handleBeforeUnload)
  streamer?.abort()
})
</script>

<style scoped lang="scss">
.share-page { height: 100vh; overflow: hidden; display: flex; flex-direction: column; background: linear-gradient(180deg, #f5f7fa 0%, #fff 100%); }
.share-header { padding: 12px 0; }
.header-inner {
  display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; gap: 12px;
  width: 100%; max-width: 1200px; margin: 0 auto; padding: 8px 24px;
  .header-titles { text-align: center; }
  h1 { margin: 0; font-size: 22px; color: #303133; }
  p { margin: 4px 0 0; color: #909399; font-size: 13px; }
}
.new-session-btn { justify-self: start; }
.header-right { justify-self: end; }
.share-body { flex: 1; min-height: 0; display: flex; gap: 16px; width: 100%; max-width: 1200px; margin: 0 auto; padding: 0 24px 16px; }
.session-sidebar { width: 200px; flex-shrink: 0; background: #fff; border-radius: 8px; box-shadow: 0 2px 12px rgba(0,0,0,0.04); display: flex; flex-direction: column; overflow: hidden; }
.session-header { padding: 14px 16px; font-size: 14px; font-weight: 600; color: #303133; border-bottom: 1px solid #ebeef5; }
.session-list { flex: 1; overflow-y: auto; padding: 8px; }
.session-item { padding: 10px 12px; border-radius: 6px; cursor: pointer; margin-bottom: 4px; display: flex; gap: 6px;
  &:hover { background: #f5f7fa; .session-delete { opacity: 1; } }
  &.active { background: #ecf5ff; .session-title { color: #409eff; } } }
.session-item-body { flex: 1; min-width: 0; }
.session-delete { flex-shrink: 0; color: #c0c4cc; opacity: 0; transition: opacity .15s; &:hover { color: #f56c6c; } }
.session-title { font-size: 13px; color: #303133; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.session-meta { font-size: 12px; color: #909399; margin-top: 4px; }
.session-empty { padding: 16px; text-align: center; font-size: 13px; color: #c0c4cc; }
.share-chat { flex: 1; min-width: 0; min-height: 0; background: #fff; border-radius: 12px; box-shadow: 0 4px 20px rgba(0,0,0,0.06); display: flex; flex-direction: column; overflow: hidden; }
.messages-container { flex: 1; min-height: 0; overflow-y: auto; padding: 20px; }
.welcome { text-align: center; padding: 40px; color: #606266; }
.msg { display: flex; gap: 10px; margin-bottom: 16px;
  &.user { flex-direction: row-reverse; .bubble { background: #409eff; color: #fff; border-radius: 12px 2px 12px 12px; } }
  &.bot { .bubble { background: #f5f7fa; border-radius: 2px 12px 12px 12px; } } }
.avatar { width: 36px; height: 36px; border-radius: 50%; background: #ecf5ff; display: flex; align-items: center; justify-content: center; flex-shrink: 0; font-size: 18px; }
.bubble { max-width: 75%; padding: 10px 14px; line-height: 1.7; font-size: 14px; word-break: break-word; }
.sources { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 4px;
  .source-tag { font-size: 11px; color: #909399; background: rgba(0,0,0,0.04); padding: 2px 6px; border-radius: 3px; } }
.input-area { display: flex; gap: 10px; padding: 14px; border-top: 1px solid #ebeef5; align-items: flex-end; }
.share-footer { text-align: center; padding: 12px; color: #c0c4cc; font-size: 12px; }
.typing { display: flex; gap: 4px;
  span { width: 8px; height: 8px; background: #909399; border-radius: 50%; animation: blink 1.4s infinite both; }
  span:nth-child(2) { animation-delay: 0.2s; }
  span:nth-child(3) { animation-delay: 0.4s; } }
.stopped-tip { margin-top: 8px; }
@keyframes blink { 0%, 80%, 100% { opacity: 0.2; } 40% { opacity: 1; } }
.error-panel { flex: 1; display: flex; align-items: center; justify-content: center; }
.pwd-lock-hint { margin-top: 8px; font-size: 12px; color: #f56c6c; line-height: 1.5; }
.kb-panel { padding: 16px; gap: 12px; }
.kb-search-bar { display: flex; gap: 10px; }
.kb-results { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 12px; }
.kb-item { background: #f5f7fa; border-radius: 8px; padding: 12px 14px; }
.kb-item-head { display: flex; justify-content: space-between; margin-bottom: 6px; }
.kb-item-content { font-size: 13px; color: #303133; line-height: 1.7; white-space: pre-wrap; }
.score { font-size: 12px; color: #909399; }
.embed { .share-header { padding: 4px 0; } .share-footer { display: none; } }
@media (max-width: 768px) {
  .header-inner { grid-template-columns: auto 1fr; padding: 6px 12px; .header-titles { text-align: left; } h1 { font-size: 18px; } }
  .header-right { display: none; }
  .share-body { flex-direction: column; padding: 0 12px 12px; gap: 12px; }
  .session-sidebar { width: 100%; max-height: 132px; }
  .bubble { max-width: 85%; }
}
</style>
