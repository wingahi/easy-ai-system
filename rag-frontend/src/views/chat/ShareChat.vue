<template>
  <div class="share-page">
    <div class="share-header">
      <div class="header-inner">
        <el-button class="new-session-btn" type="primary" :icon="Plus" @click="newSession">新建会话</el-button>
        <div class="header-titles">
          <h1>{{ window?.name || 'AI 助手' }}</h1>
          <p>{{ window?.description || '基于知识库的 AI 助手' }}</p>
        </div>
        <div class="header-right"></div>
      </div>
    </div>

    <div class="share-body">
      <!-- 左侧历史会话 -->
      <aside class="session-sidebar">
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

      <div class="share-chat">
        <div class="messages-container" ref="messagesRef">
          <div v-if="messages.length === 0" class="welcome">
            <h2>👋 你好，我是 {{ window?.name }}</h2>
            <p>请输入你的问题，我会基于知识库为你解答</p>
          </div>
          <div v-for="(msg, idx) in messages" :key="idx"
               :class="['msg', msg.role === 'USER' ? 'user' : 'bot']">
            <div class="avatar">{{ msg.role === 'USER' ? '🙂' : '🤖' }}</div>
            <div class="bubble">
              <div v-html="renderMarkdown(msg.content)"></div>
              <div v-if="msg.streaming" class="typing">
                <span></span><span></span><span></span>
              </div>
              <div v-if="dedupSources(msg.sources).length" class="sources">
                <span v-for="(s, i) in dedupSources(msg.sources)" :key="i" class="source-tag">
                  📎 {{ s.kbName }}/{{ s.fileName }}
                </span>
              </div>
            </div>
          </div>
        </div>
        <div class="input-area">
          <el-input v-model="input" type="textarea" :rows="2"
                    placeholder="输入消息..." @keydown.enter.exact.prevent="send"
                    :disabled="streaming" resize="none" />
          <el-button type="primary" :icon="Promotion" :loading="streaming" @click="send">发送</el-button>
        </div>
      </div>
    </div>

    <div class="share-footer">
      <span>Powered by WGH RAG System</span>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Promotion, Plus, Delete } from '@element-plus/icons-vue'
import { marked } from 'marked'
import {
  windowGetByShareToken, streamShareApi,
  shareSessions, shareCreateSession, shareDeleteSession, shareMessages
} from '../../api'

const route = useRoute()
const token = route.params.token
const window = ref(null)
const messages = ref([])
const sessions = ref([])
const input = ref('')
const streaming = ref(false)
const messagesRef = ref(null)
let streamer = null

// 分享页已要求登录：会话归属取当前登录用户名，不再使用匿名 sessionKey
const currentSessionId = ref(null)

marked.setOptions({ breaks: true })

const load = async () => {
  try {
    const res = await windowGetByShareToken(token)
    window.value = res.data
    await loadSessions()
    await loadHistory()
  } catch (e) {
    ElMessage.error('分享链接无效或已停用')
  }
}

// 加载历史会话列表
const loadSessions = async () => {
  try {
    const res = await shareSessions(token)
    sessions.value = res.data || []
    if (!currentSessionId.value && sessions.value.length) {
      currentSessionId.value = sessions.value[0].id
    }
  } catch (e) {
    console.error('加载会话列表失败', e)
  }
}

// 加载当前会话的历史消息
const loadHistory = async () => {
  try {
    const res = await shareMessages(token, {
      sessionId: currentSessionId.value || undefined,
      page: 1, size: 50
    })
    const records = res.data?.records || []
    messages.value = records.map(m => ({
      role: m.role,
      content: m.content,
      sources: m.sources ? safeParseSources(m.sources) : []
    }))
    await nextTick(scroll)
  } catch (e) {
    console.error('加载历史消息失败', e)
  }
}

const selectSession = async (s) => {
  if (streaming.value || s.id === currentSessionId.value) return
  currentSessionId.value = s.id
  messages.value = []
  await loadHistory()
}

// 新建会话：先调后端创建，再清空消息区
const newSession = async () => {
  if (streaming.value) {
    streamer?.abort()
    streaming.value = false
  }
  try {
    const res = await shareCreateSession(token)
    const created = res.data
    currentSessionId.value = created.id
    sessions.value = [created, ...sessions.value]
    messages.value = []
  } catch (e) {
    ElMessage.error('新建会话失败')
  }
}

const formatTime = (t) => {
  if (!t) return ''
  const d = new Date(t)
  if (isNaN(d.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  const sameDay = d.toDateString() === new Date().toDateString()
  return sameDay
    ? `${pad(d.getHours())}:${pad(d.getMinutes())}`
    : `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// 删除会话：从左侧列表移除；若删除的是当前会话，自动选中下一条
const deleteSession = async (s) => {
  try {
    await shareDeleteSession(token, s.id)
    const idx = sessions.value.findIndex(x => x.id === s.id)
    if (idx >= 0) sessions.value.splice(idx, 1)
    ElMessage.success('会话已删除')

    if (currentSessionId.value === s.id) {
      const next = sessions.value[0]
      if (next) {
        currentSessionId.value = next.id
        await loadHistory()
      } else {
        currentSessionId.value = null
        messages.value = []
      }
    }
  } catch (e) {
    ElMessage.error('删除失败: ' + (e.message || ''))
  }
}

const send = async () => {
  const text = input.value.trim()
  if (!text || streaming.value || !window.value) return
  input.value = ''
  
  messages.value.push({ role: 'USER', content: text })
  const aiMsg = { role: 'ASSISTANT', content: '', streaming: true, sources: [] }
  messages.value.push(aiMsg)
  await scroll()
  streaming.value = true

  streamer = streamShareApi(token, text, currentSessionId.value)
  streamer.doStream(
    (event, data) => {
      // 后端 JSON 包装 {text}，兼容纯文本
      if (event === 'start') {
        if (data?.sessionId) currentSessionId.value = data.sessionId
      } else if (event === 'content') aiMsg.content += (data && typeof data === 'object' ? (data.text ?? '') : data)
      else if (event === 'sources') aiMsg.sources = data
      else if (event === 'done') {
        streaming.value = false
        aiMsg.streaming = false
        if (data?.sessionId) currentSessionId.value = data.sessionId
        loadSessions()
      }
      nextTick(scroll)
    },
    () => { streaming.value = false; aiMsg.streaming = false },
    (err) => { streaming.value = false; aiMsg.streaming = false; aiMsg.content += '\n⚠️ ' + err.message }
  )
}

const scroll = () => nextTick(() => { if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight })
// marked v14 默认异步返回 Promise，需显式同步解析供 v-html 使用
const renderMarkdown = (t) => marked.parse(t || '', { async: false })

const safeParseSources = (json) => {
  try { return JSON.parse(json) || [] } catch { return [] }
}

// 按 fileName 去重，保留 score 最高的一条
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

onMounted(load)
onUnmounted(() => streamer?.abort())
</script>

<style scoped lang="scss">
.share-page {
  height: 100vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #f5f7fa 0%, #fff 100%);
}
.share-header {
  padding: 12px 0;
}
/* 头部与下方内容区共用同一套居中容器宽度，避免超宽屏下按钮与内容错位 */
.header-inner {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 12px;
  width: 100%;
  max-width: 1400px;
  margin: 0 auto;
  padding: 8px 24px;
  .header-titles { text-align: center; }
  h1 { margin: 0; font-size: 24px; color: #303133; }
  p { margin: 4px 0 0; color: #909399; font-size: 13px; }
}
.new-session-btn {
  justify-self: start;
}
.share-body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 16px;
  width: 100%;
  max-width: 1400px;
  margin: 0 auto;
  padding: 0 24px 16px;
}
.session-sidebar {
  width: 200px;
  flex-shrink: 0;
  min-height: 0;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.session-header {
  padding: 14px 16px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  border-bottom: 1px solid #ebeef5;
}
.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}
.session-item {
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
  transition: background .15s;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 6px;

  &:hover {
    background: #f5f7fa;
    .session-delete { opacity: 1; }
  }
  &.active {
    background: #ecf5ff;
    .session-title { color: #409eff; }
  }
}
.session-item-body {
  flex: 1;
  min-width: 0;
}
.session-delete {
  flex-shrink: 0;
  margin-top: 2px;
  color: #c0c4cc;
  font-size: 14px;
  opacity: 0;
  transition: opacity .15s, color .15s;
  &:hover { color: #f56c6c; }
}
.session-title {
  font-size: 13px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
.session-empty {
  padding: 16px;
  text-align: center;
  font-size: 13px;
  color: #c0c4cc;
}
.share-chat {
  flex: 1;
  min-width: 0;
  min-height: 0;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0,0,0,0.06);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.messages-container {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px;
}
.welcome { text-align: center; padding: 40px; color: #606266; }
.msg {
  display: flex; gap: 10px; margin-bottom: 16px;
  &.user { flex-direction: row-reverse; .bubble { background: #409eff; color: #fff; border-radius: 12px 2px 12px 12px; } }
  &.bot { .bubble { background: #f5f7fa; border-radius: 2px 12px 12px 12px; } }
}
.avatar {
  width: 36px; height: 36px; border-radius: 50%;
  background: #ecf5ff; display: flex; align-items: center; justify-content: center;
  flex-shrink: 0; font-size: 18px;
}
.bubble {
  max-width: 75%;
  padding: 10px 14px;
  line-height: 1.7;
  font-size: 14px;
  word-break: break-word;
}
.sources {
  margin-top: 8px;
  display: flex; flex-wrap: wrap; gap: 4px;
  .source-tag {
    font-size: 11px; color: #909399;
    background: rgba(0,0,0,0.04); padding: 2px 6px; border-radius: 3px;
  }
}
.input-area {
  display: flex; gap: 10px; padding: 14px;
  border-top: 1px solid #ebeef5;
  align-items: flex-end;
}
.share-footer {
  text-align: center;
  padding: 12px;
  color: #c0c4cc;
  font-size: 12px;
}
@media (max-width: 768px) {
  .header-inner {
    grid-template-columns: auto 1fr;
    padding: 6px 12px;
    .header-titles { text-align: left; }
    h1 { font-size: 18px; }
  }
  .header-right { display: none; }
  .share-body {
    flex-direction: column;
    padding: 0 12px 12px;
    gap: 12px;
  }
  .session-sidebar {
    width: 100%;
    max-height: 132px;
  }
  .messages-container { padding: 12px; }
  .bubble { max-width: 85%; }
}
.typing { display: flex; gap: 4px;
  span { width: 8px; height: 8px; background: #909399; border-radius: 50%; animation: blink 1.4s infinite both; }
  span:nth-child(2) { animation-delay: 0.2s; }
  span:nth-child(3) { animation-delay: 0.4s; }
}
@keyframes blink {
  0%, 80%, 100% { opacity: 0.2; }
  40% { opacity: 1; }
}
</style>
