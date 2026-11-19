<script setup lang="ts">
import { ref } from 'vue';
import TabManagerModal from './components/TabManagerModal.vue';
import { useKioskTabs } from './composables/useKioskTabs';

const {
  state,
  activeTab,
  selectTab,
  reloadTab,
  getTabKey,
  getResolvedUrl,
  addTab,
  updateTab,
  removeTab,
  moveTab,
  setKioskLocked,
} = useKioskTabs();

const showSettings = ref(false);

let activeTapCount = 0;
let activeTapTimer: ReturnType<typeof setTimeout> | null = null;

function handleTabClick(tabId: string) {
  if (state.activeTabId === tabId && state.kioskLocked) {
    activeTapCount += 1;
    if (activeTapTimer) clearTimeout(activeTapTimer);
    if (activeTapCount >= 5) {
      activeTapCount = 0;
      showSettings.value = true;
      return;
    }
    activeTapTimer = setTimeout(() => {
      activeTapCount = 0;
    }, 1500);
    return;
  }

  activeTapCount = 0;
  selectTab(tabId);
}
</script>

<template>
  <div class="kiosk-shell">
    <header class="top-tabs" role="tablist" aria-label="Webpage tabs">
      <div class="tabs-scroll">
        <button
          v-for="tab in state.tabs"
          :key="tab.id"
          type="button"
          role="tab"
          :aria-selected="tab.id === state.activeTabId"
          class="tab-btn"
          :class="{ active: tab.id === state.activeTabId }"
          @click="handleTabClick(tab.id)"
        >
          <span class="tab-indicator" />
          <span class="tab-label">{{ tab.title }}</span>
        </button>
      </div>

      <div v-if="!state.kioskLocked" class="tab-actions">
        <button
          v-if="activeTab"
          type="button"
          class="action-btn"
          title="Reload active tab"
          aria-label="Reload active tab"
          @click="reloadTab(activeTab.id)"
        >
          ↻
        </button>
        <button
          type="button"
          class="action-btn"
          title="Configure tabs"
          aria-label="Configure tabs"
          @click="showSettings = true"
        >
          ⚙
        </button>
      </div>
    </header>

    <main class="viewport">
      <template v-if="state.tabs.length > 0">
        <template v-for="tab in state.tabs" :key="getTabKey(tab.id)">
          <template v-if="tab.id === state.activeTabId">
            <iframe
              v-if="getResolvedUrl(tab)"
              :src="getResolvedUrl(tab)"
              :title="tab.title"
              class="tab-frame"
              allow="fullscreen; autoplay"
              referrerpolicy="no-referrer-when-downgrade"
            />
            <div v-else class="empty-state">
              <p>Resolving {{ tab.title }}…</p>
            </div>
          </template>
        </template>
      </template>

      <div v-else class="empty-state">
        <p>No tabs configured.</p>
        <button type="button" class="setup-btn" @click="showSettings = true">
          Add Webpage Tab
        </button>
      </div>
    </main>

    <TabManagerModal
      v-if="showSettings"
      :tabs="state.tabs"
      :kiosk-locked="state.kioskLocked"
      @close="showSettings = false"
      @add="addTab"
      @update="updateTab"
      @remove="removeTab"
      @move="moveTab"
      @toggle-lock="setKioskLocked"
    />
  </div>
</template>

<style scoped>
.kiosk-shell {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: var(--bg-chrome);
}

.top-tabs {
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  height: 38px;
  min-height: 38px;
  background: var(--bg-chrome);
  border-bottom: 1px solid var(--border-subtle);
  user-select: none;
  padding: 0 4px;
}

.tabs-scroll {
  display: flex;
  align-items: stretch;
  flex: 1;
  min-width: 0;
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-width: none;
  padding-top: 4px;
}

.tabs-scroll::-webkit-scrollbar {
  display: none;
}

.tab-btn {
  display: inline-flex;
  align-items: center;
  padding: 0 14px;
  margin-right: 4px;
  border: 1px solid transparent;
  border-bottom: none;
  border-radius: 6px 6px 0 0;
  background: var(--bg-tab);
  color: var(--text-muted);
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  cursor: pointer;
}

.tab-btn:hover {
  background: var(--bg-tab-hover);
  color: var(--text-primary);
}

.tab-btn.active {
  background: var(--bg-tab-active);
  color: var(--text-primary);
  border-color: var(--border-subtle);
}

.tab-indicator {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-muted);
  opacity: 0.45;
  flex-shrink: 0;
  margin-right: 8px;
}

.tab-btn.active .tab-indicator {
  background: var(--accent);
  opacity: 1;
}

.tab-label {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tab-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  padding-right: 2px;
  margin-left: 4px;
}

.action-btn {
  width: 36px;
  height: 36px;
  margin-left: 4px;
  border-radius: 8px;
  border: 1px solid transparent;
  background: transparent;
  color: var(--text-muted);
  font-size: 17px;
  line-height: 1;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.action-btn:hover {
  background: var(--bg-tab-hover);
  color: var(--text-primary);
}

.viewport {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  background: #0b0d11;
}

.tab-frame {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  width: 100%;
  height: 100%;
  border: none;
  display: block;
  background: #ffffff;
}

.empty-state {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
}

.empty-state p {
  margin: 0 0 14px;
}

.setup-btn {
  padding: 10px 18px;
  border-radius: 8px;
  border: none;
  background: var(--accent);
  color: #081018;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}
</style>
