import { createRouter, createWebHistory } from 'vue-router'

import AppShell from './components/AppShell.vue'
import LoginView from './features/auth/LoginView.vue'
import RegisterView from './features/auth/RegisterView.vue'
import OcrCredentialView from './features/settings/OcrCredentialView.vue'
import { useSessionStore } from './stores/session'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView },
    { path: '/register', component: RegisterView },
    {
      path: '/settings',
      component: AppShell,
      children: [{ path: 'ocr', component: OcrCredentialView }],
    },
    { path: '/', redirect: '/settings/ocr' },
  ],
})

router.beforeEach(async (to) => {
  const session = useSessionStore()
  if (!session.ready) {
    await session.restore()
  }

  const requiresAuth = to.path === '/settings/ocr' || to.path.startsWith('/settings/')
  if (requiresAuth && !session.user) {
    return '/login'
  }
  if ((to.path === '/login' || to.path === '/register') && session.user) {
    return '/settings/ocr'
  }
  return true
})
