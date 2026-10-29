import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const routes = [
  { path: '/login', name: 'Login', component: () => import('../views/login/Login.vue') },

  // 分享页面（需登录；登录后历史会话按当前用户名 + windowId 查询，与 shareToken 解耦）
  { path: '/share/:token', name: 'ShareChat', component: () => import('../views/chat/ShareChat.vue'), meta: { title: '分享对话', requiresAuth: true } },
  
  // 管理端布局
  {
    path: '/',
    component: () => import('../components/Layout.vue'),
    redirect: '/kb',
    children: [
      { path: 'kb', name: 'KnowledgeBase', component: () => import('../views/kb/KnowledgeBase.vue'), meta: { title: '知识库管理' } },
      { path: 'kb/:id/files', name: 'KBFiles', component: () => import('../views/kb/KBFiles.vue'), meta: { title: '文件管理' } },
      { path: 'kb/:id/search', name: 'KBSearch', component: () => import('../views/kb/KBSearch.vue'), meta: { title: '知识库搜索' } },
      { path: 'models/chat', name: 'ChatModels', component: () => import('../views/models/ChatModels.vue'), meta: { title: '大模型管理' } },
      { path: 'models/embedding', name: 'EmbeddingModels', component: () => import('../views/models/EmbeddingModels.vue'), meta: { title: '嵌入模型管理' } },
      { path: 'chat/windows', name: 'ChatWindows', component: () => import('../views/chat-admin/ChatWindows.vue'), meta: { title: '聊天窗口' } },
      { path: 'chat/windows/:id/playground', name: 'ChatPlayground', component: () => import('../views/chat/ChatPlayground.vue'), meta: { title: '调试对话' } },
      { path: 'tools', name: 'Tools', component: () => import('../views/tools/ToolManagement.vue'), meta: { title: '工具管理' } },
      { path: 'workflows', name: 'Workflows', component: () => import('../views/workflow/WorkflowList.vue'), meta: { title: '工作流编排' } },
      { path: 'workflows/:id/edit', name: 'WorkflowEditor', component: () => import('../views/workflow/WorkflowEditor.vue'), meta: { title: '工作流编辑器' } },
      { path: 'multi-agent/crews', name: 'MultiAgentCrews', component: () => import('../views/multi-agent/MultiAgentCrews.vue'), meta: { title: '多 Agent 协作' } },
      { path: 'multi-agent/playground', name: 'MultiAgentPlayground', component: () => import('../views/multi-agent/MultiAgentPlayground.vue'), meta: { title: '多 Agent 运行调试' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫
router.beforeEach((to, from, next) => {
  const userStore = useUserStore()

  if (to.path.startsWith('/login')) {
    next()
    return
  }

  if (!userStore.isLoggedIn) {
    // 未登录访问任何页面（含分享页）→ 跳登录页并带 redirect，登录后回跳
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  next()
})

export default router
