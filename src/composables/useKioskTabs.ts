import { computed, reactive, ref, watch } from 'vue';
import { DEFAULT_SITES } from '../config/sites';
import { getCachedSiteUrl, resolveSiteUrl } from '../services/hostResolver';
import type { KioskState, KioskTab } from '../types/kiosk';

const STORAGE_KEY = 'klipperkiosk-state-v1';

export function normalizeUrl(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed) return '';
  if (/^https?:\/\//i.test(trimmed)) {
    return trimmed;
  }
  return `http://${trimmed}`;
}

function createId(): string {
  return `tab-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

function loadInitialState(): KioskState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<KioskState>;
      if (Array.isArray(parsed.tabs) && parsed.tabs.length > 0) {
        const activeExists = parsed.tabs.some((t) => t.id === parsed.activeTabId);
        return {
          tabs: parsed.tabs,
          activeTabId: activeExists ? (parsed.activeTabId as string) : parsed.tabs[0].id,
          kioskLocked: Boolean(parsed.kioskLocked),
        };
      }
    }
  } catch {
    // Fall back to defaults if storage is unavailable or corrupted
  }

  return {
    tabs: DEFAULT_SITES.map((t) => ({ ...t })),
    activeTabId: DEFAULT_SITES[0]?.id ?? '',
    kioskLocked: false,
  };
}

export function useKioskTabs() {
  const state = reactive<KioskState>(loadInitialState());
  const reloadCounters = ref<Record<string, number>>({});
  const resolvedUrls = ref<Record<string, string>>({});

  async function resolveTab(tab: KioskTab) {
    const cached = getCachedSiteUrl(tab.url);
    if (cached && !resolvedUrls.value[tab.id]) {
      resolvedUrls.value[tab.id] = cached;
    }
    const { url } = await resolveSiteUrl(tab.url);
    if (resolvedUrls.value[tab.id] !== url) {
      resolvedUrls.value[tab.id] = url;
    }
  }

  function resolveAllTabs() {
    const active = state.tabs.find((t) => t.id === state.activeTabId);
    if (active) {
      void resolveTab(active);
    }
    for (const tab of state.tabs) {
      if (tab.id !== state.activeTabId) {
        void resolveTab(tab);
      }
    }
  }

  resolveAllTabs();

  watch(
    () => ({
      tabs: state.tabs,
      activeTabId: state.activeTabId,
      kioskLocked: state.kioskLocked,
    }),
    (next) => {
      try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
      } catch {
        // Ignore quota/storage errors
      }
    },
    { deep: true }
  );

  const activeTab = computed(() =>
    state.tabs.find((tab) => tab.id === state.activeTabId) ?? state.tabs[0] ?? null
  );

  function selectTab(id: string) {
    if (state.tabs.some((tab) => tab.id === id)) {
      state.activeTabId = id;
    }
  }

  function reloadTab(id: string) {
    const tab = state.tabs.find((t) => t.id === id);
    if (tab) {
      void resolveTab(tab);
    }
    reloadCounters.value[id] = (reloadCounters.value[id] ?? 0) + 1;
  }

  function getTabKey(id: string): string {
    return `${id}:${reloadCounters.value[id] ?? 0}`;
  }

  function getResolvedUrl(tab: KioskTab): string | undefined {
    return resolvedUrls.value[tab.id];
  }

  function addTab(title: string, url: string) {
    const normalized = normalizeUrl(url);
    if (!normalized) return;
    const newTab: KioskTab = {
      id: createId(),
      title: title.trim() || normalized.replace(/^https?:\/\//i, ''),
      url: normalized,
    };
    state.tabs.push(newTab);
    state.activeTabId = newTab.id;
    void resolveTab(newTab);
  }

  function updateTab(id: string, title: string, url: string) {
    const tab = state.tabs.find((t) => t.id === id);
    if (!tab) return;
    const normalized = normalizeUrl(url);
    if (!normalized) return;
    tab.title = title.trim() || normalized.replace(/^https?:\/\//i, '');
    tab.url = normalized;
    delete resolvedUrls.value[id];
    reloadTab(id);
  }

  function removeTab(id: string) {
    const index = state.tabs.findIndex((t) => t.id === id);
    if (index === -1) return;
    state.tabs.splice(index, 1);
    delete resolvedUrls.value[id];
    if (state.activeTabId === id) {
      const fallback = state.tabs[index] ?? state.tabs[index - 1] ?? null;
      state.activeTabId = fallback ? fallback.id : '';
    }
  }

  function moveTab(id: string, direction: -1 | 1) {
    const index = state.tabs.findIndex((t) => t.id === id);
    const target = index + direction;
    if (index === -1 || target < 0 || target >= state.tabs.length) return;
    const [item] = state.tabs.splice(index, 1);
    state.tabs.splice(target, 0, item);
  }

  function setKioskLocked(locked: boolean) {
    state.kioskLocked = locked;
  }

  return {
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
  };
}
