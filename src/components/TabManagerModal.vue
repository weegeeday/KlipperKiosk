<script setup lang="ts">
import { ref } from 'vue';
import type { KioskTab } from '../types/kiosk';

const props = defineProps<{
  tabs: KioskTab[];
  kioskLocked: boolean;
}>();

const emit = defineEmits<{
  close: [];
  add: [title: string, url: string];
  update: [id: string, title: string, url: string];
  remove: [id: string];
  move: [id: string, direction: -1 | 1];
  toggleLock: [locked: boolean];
}>();

const newTitle = ref('');
const newUrl = ref('');
const editingId = ref<string | null>(null);
const editTitle = ref('');
const editUrl = ref('');

function handleAdd() {
  if (!newUrl.value.trim()) return;
  emit('add', newTitle.value, newUrl.value);
  newTitle.value = '';
  newUrl.value = '';
}

function startEdit(tab: KioskTab) {
  editingId.value = tab.id;
  editTitle.value = tab.title;
  editUrl.value = tab.url;
}

function saveEdit(id: string) {
  if (!editUrl.value.trim()) return;
  emit('update', id, editTitle.value, editUrl.value);
  editingId.value = null;
}

function cancelEdit() {
  editingId.value = null;
}
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <div class="modal-card" role="dialog" aria-modal="true" aria-label="Kiosk Tab Settings">
      <header class="modal-header">
        <h2>Kiosk Tabs</h2>
        <button class="icon-btn" type="button" aria-label="Close" @click="emit('close')">
          ✕
        </button>
      </header>

      <section class="add-form">
        <input
          v-model="newTitle"
          type="text"
          placeholder="Tab name (e.g. Mainsail)"
          class="field"
        />
        <input
          v-model="newUrl"
          type="url"
          placeholder="URL (e.g. http://192.168.1.100)"
          class="field field-url"
          @keyup.enter="handleAdd"
        />
        <button
          type="button"
          class="btn btn-primary"
          :disabled="!newUrl.trim()"
          @click="handleAdd"
        >
          Add Tab
        </button>
      </section>

      <ul class="tab-list">
        <li v-for="(tab, index) in props.tabs" :key="tab.id" class="tab-item">
          <template v-if="editingId === tab.id">
            <div class="edit-row">
              <input v-model="editTitle" type="text" placeholder="Tab name" class="field" />
              <input
                v-model="editUrl"
                type="url"
                placeholder="URL"
                class="field field-url"
                @keyup.enter="saveEdit(tab.id)"
              />
              <div class="row-actions">
                <button type="button" class="btn btn-primary" @click="saveEdit(tab.id)">
                  Save
                </button>
                <button type="button" class="btn" @click="cancelEdit">Cancel</button>
              </div>
            </div>
          </template>
          <template v-else>
            <div class="tab-meta">
              <span class="tab-name">{{ tab.title }}</span>
              <span class="tab-url">{{ tab.url }}</span>
            </div>
            <div class="row-actions">
              <button
                type="button"
                class="btn"
                :disabled="index === 0"
                title="Move left"
                @click="emit('move', tab.id, -1)"
              >
                ←
              </button>
              <button
                type="button"
                class="btn"
                :disabled="index === props.tabs.length - 1"
                title="Move right"
                @click="emit('move', tab.id, 1)"
              >
                →
              </button>
              <button type="button" class="btn" @click="startEdit(tab)">Edit</button>
              <button
                type="button"
                class="btn btn-danger"
                @click="emit('remove', tab.id)"
              >
                Delete
              </button>
            </div>
          </template>
        </li>
      </ul>

      <footer class="modal-footer">
        <label class="lock-toggle">
          <input
            type="checkbox"
            :checked="props.kioskLocked"
            @change="emit('toggleLock', ($event.target as HTMLInputElement).checked)"
          />
          <span>
            Lock kiosk controls (hides settings &amp; reload buttons; tap active tab 5× to unlock)
          </span>
        </label>
        <button type="button" class="btn btn-primary" @click="emit('close')">Done</button>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 100;
  background: rgba(0, 0, 0, 0.72);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}

.modal-card {
  width: 720px;
  max-width: 100%;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  background: #171a21;
  border: 1px solid var(--border-subtle);
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.55);
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border-subtle);
}

.modal-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.icon-btn {
  background: transparent;
  border: none;
  color: var(--text-muted);
  font-size: 18px;
  cursor: pointer;
  padding: 6px 10px;
  border-radius: 6px;
}

.icon-btn:hover {
  background: var(--bg-tab-hover);
  color: var(--text-primary);
}

.add-form {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border-subtle);
  background: #13161c;
}

.field {
  flex: 1 1 160px;
  min-width: 0;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid var(--border-subtle);
  background: #0e1015;
  color: var(--text-primary);
  font-size: 14px;
}

.field-url {
  flex: 2 1 240px;
}

.tab-list {
  list-style: none;
  margin: 0;
  padding: 10px 18px;
  overflow-y: auto;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tab-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--bg-tab);
  border: 1px solid var(--border-subtle);
  flex-wrap: wrap;
}

.tab-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1 1 200px;
}

.tab-name {
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tab-url {
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.edit-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  width: 100%;
}

.row-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.btn {
  padding: 8px 12px;
  border-radius: 8px;
  border: 1px solid var(--border-subtle);
  background: var(--bg-tab-hover);
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.btn-primary {
  background: var(--accent);
  border-color: var(--accent);
  color: #081018;
  font-weight: 600;
}

.btn-danger {
  background: var(--danger-soft);
  border-color: rgba(248, 113, 113, 0.35);
  color: var(--danger);
}

.modal-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 18px;
  border-top: 1px solid var(--border-subtle);
  background: #13161c;
  flex-wrap: wrap;
}

.lock-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-muted);
  cursor: pointer;
}
</style>

