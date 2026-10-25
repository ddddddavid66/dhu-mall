import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router'
import { useSessionStore } from './stores/session'
import './style.css'

const pinia = createPinia()
const app = createApp(App)
app.use(pinia)
const session = useSessionStore(pinia)
window.addEventListener('yueji:session-expired', () => session.expire())
app.use(router)
app.mount('#app')
