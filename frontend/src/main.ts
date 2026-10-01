import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from './App.vue'
import { router } from './router'
import { useSessionStore } from './stores/session'
import './styles/tokens.css'
import './styles/base.css'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)

const session = useSessionStore(pinia)
await session.restore()
await router.isReady()
app.mount('#app')
