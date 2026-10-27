<template>
  <el-container class="layout-container">
    <el-aside :width="collapsed ? '64px' : '220px'" class="sidebar">
      <div class="logo">
        <span v-if="!collapsed">WGH RAG</span>
        <span v-else>RAG</span>
      </div>
      <el-menu
        :default-active="$route.path"
        :collapse="collapsed"
        background-color="#1d1e1f"
        text-color="#c0c4cc"
        active-text-color="#409eff"
        router
      >
        <el-menu-item index="/kb">
          <el-icon><FolderOpened /></el-icon>
          <template #title>知识库管理</template>
        </el-menu-item>
        
        <el-sub-menu index="models">
          <template #title>
            <el-icon><Cpu /></el-icon>
            <span>模型管理</span>
          </template>
          <el-menu-item index="/models/chat">大模型配置</el-menu-item>
          <el-menu-item index="/models/embedding">嵌入模型</el-menu-item>
        </el-sub-menu>
        
        <el-sub-menu index="chat">
          <template #title>
            <el-icon><ChatDotRound /></el-icon>
            <span>聊天管理</span>
          </template>
          <el-menu-item index="/chat/windows">聊天窗口</el-menu-item>
        </el-sub-menu>
        
        <el-menu-item index="/tools">
          <el-icon><SetUp /></el-icon>
          <template #title>工具管理</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="collapsed = !collapsed">
            <Fold v-if="!collapsed" />
            <Expand v-else />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>{{ $route.meta?.title || '首页' }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-dropdown @command="handleCommand">
            <span class="user-info">
              <el-avatar :size="32">{{ userStore.username?.[0]?.toUpperCase() }}</el-avatar>
              <span class="username">{{ userStore.username }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">
                  <el-icon><SwitchButton /></el-icon>退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main-content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { 
  FolderOpened, Cpu, ChatDotRound, SetUp, 
  Fold, Expand, SwitchButton 
} from '@element-plus/icons-vue'
import { useUserStore } from '../stores/user'

const router = useRouter()
const userStore = useUserStore()
const collapsed = ref(false)

const handleCommand = (cmd) => {
  if (cmd === 'logout') {
    userStore.logout()
    router.push('/login')
  }
}
</script>

<style scoped lang="scss">
.layout-container {
  height: 100vh;
}
.sidebar {
  background: #1d1e1f;
  transition: width 0.2s;
  overflow: hidden;
  .logo {
    height: 56px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #409eff;
    font-size: 18px;
    font-weight: bold;
    border-bottom: 1px solid #2d2e2f;
  }
  .el-menu { border-right: none; }
}
.header {
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-shadow: 0 1px 4px rgba(0,0,0,0.08);
  padding: 0 20px;
  height: 56px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
  .collapse-btn {
    font-size: 20px;
    cursor: pointer;
    &:hover { color: #409eff; }
  }
}
.header-right {
  .user-info {
    display: flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
    .username { font-size: 14px; }
  }
}
.main-content {
  background: var(--bg-color);
  padding: 0;
  overflow-y: auto;
}
.fade-enter-active, .fade-leave-active { transition: opacity 0.2s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
