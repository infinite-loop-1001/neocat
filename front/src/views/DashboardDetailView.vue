<template>
  <section class="workspace-panel">
    <header class="panel-head">
      <h2>{{ board?.name ?? "大盘" }}</h2>
      <router-link class="btn btn-ghost" to="/dashboards">← 返回大盘列表</router-link>
    </header>

    <p class="hint">
      一张卡片只绑定<strong>一个服务 + 一个指标对象</strong>；同一指标可引用多个统计项并做四则运算。
      界面不列单位，但系统内部校验单位兼容，非法公式不能保存。
    </p>

    <div class="card-editor">
      <label class="field-label" for="service">服务</label>
      <select id="service" v-model="draft.service" class="select-field">
        <option v-for="s in services" :key="s.name" :value="s.name">{{ s.name }}</option>
      </select>

      <label class="field-label" for="type">指标对象</label>
      <select id="type" v-model="draft.targetType" class="select-field">
        <option value="URL">URL</option>
        <option value="SQL">SQL</option>
        <option value="CACHE">CACHE</option>
      </select>

      <input v-model="draft.targetName" class="field" placeholder="Name，例如 POST /orders" />

      <label class="field-label" for="formula">公式</label>
      <input id="formula" v-model="draft.formula" class="field mono" placeholder="例如 failures / hits" />

      <label class="field-label" for="threshold">阈值线（视觉对照）</label>
      <div class="threshold-row">
        <select id="threshold" v-model="draft.thresholdDirection" class="select-field">
          <option value="ABOVE">高于</option>
          <option value="BELOW">低于</option>
        </select>
        <input v-model.number="draft.thresholdValue" class="field" type="number" step="0.01" />
      </div>

      <button class="btn btn-primary" type="button" @click="save">保存卡片</button>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    </div>

    <table class="data-table">
      <thead>
        <tr>
          <th>指标对象</th>
          <th>公式</th>
          <th>单位</th>
          <th>阈值线</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="card in cards"
          :key="card.id"
          class="clickable-row"
          :class="{ 'is-focused': card.id === selectedCardId }"
          role="link"
          tabindex="0"
          :aria-label="`查看卡片 ${card.targetType} ${card.targetName}`"
          @click="select(card)"
          @keydown.enter="select(card)"
        >
          <td class="strong">{{ card.targetType }} · {{ card.targetName }}</td>
          <td class="mono">{{ card.formula }}</td>
          <td class="mono muted">{{ card.unit }}</td>
          <td class="mono muted">
            {{ card.thresholdLines.length ? `${card.thresholdLines[0].direction} ${card.thresholdLines[0].value}` : "—" }}
          </td>
          <td class="actions">
            <button class="btn btn-ghost" type="button" @click.stop="toAlert(card)">一键创建组织告警</button>
            <button class="btn btn-ghost" type="button" @click.stop="remove(card.id)">删除</button>
          </td>
        </tr>
        <tr v-if="!cards.length">
          <td colspan="5" class="empty">该大盘还没有卡片</td>
        </tr>
      </tbody>
    </table>

    <ReportChart v-if="selectedCardId" :series="chartSeries" :threshold-lines="selectedThresholds" :caption="chartCaption" />

    <p class="hint">公式变更会让关联的组织告警跟随新公式，并保存为关闭、窗口清零。</p>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, ApiError } from "../api";
import type { Point } from "../api/types";
import type { Card, Dashboard } from "../mock/model";
import ReportChart from "../components/ReportChart.vue";

const route = useRoute();
const router = useRouter();
const dashboardId = computed(() => Number(route.params.id));

const board = ref<Dashboard | null>(null);
const cards = ref<Card[]>([]);
const services = ref<{ name: string; instances: string[] }[]>([]);
const points = ref<Point[]>([]);
const selectedCardId = ref<number | null>(null);
const error = ref("");

const draft = ref({
  service: "order",
  targetType: "URL",
  targetName: "POST /orders",
  formula: "failures / hits",
  unit: "RATE",
  thresholdDirection: "ABOVE" as "ABOVE" | "BELOW",
  thresholdValue: 0.05,
});

const chartSeries = computed(() => [
  { name: cards.value.find((c) => c.id === selectedCardId.value)?.formula ?? "卡片", points: points.value },
]);
const selectedThresholds = computed(() => cards.value.find((c) => c.id === selectedCardId.value)?.thresholdLines ?? []);
const chartCaption = computed(() => "缺口表示任一输入缺数；除零显示为不可计算，二者语义不同");

async function load() {
  const boards = await api<Dashboard[]>("/dashboards");
  board.value = boards.find((b) => b.id === dashboardId.value) ?? null;
  cards.value = await api<Card[]>("/cards", { query: { dashboardId: dashboardId.value } });
  services.value = await api<{ name: string; instances: string[] }[]>("/services");
}

async function save() {
  error.value = "";
  try {
    await api(`/dashboards/${dashboardId.value}/cards`, {
      method: "POST",
      body: {
        service: draft.value.service,
        targetKind: "TRANSACTION",
        targetType: draft.value.targetType,
        targetName: draft.value.targetName,
        formula: draft.value.formula,
        unit: draft.value.unit,
        thresholdLines: [{ direction: draft.value.thresholdDirection, value: draft.value.thresholdValue }],
      },
    });
    await reloadCards();
  } catch (e) {
    // 单位不兼容等错误在此展示（后端校验）
    error.value = e instanceof ApiError ? e.message : "保存失败";
  }
}

async function reloadCards() {
  cards.value = await api<Card[]>("/cards", { query: { dashboardId: dashboardId.value } });
}

async function select(card: Card) {
  selectedCardId.value = card.id;
  const response = await api<{ points: Point[] }>(`/cards/${card.id}/series`, {
    query: { range: card.timeRange },
  });
  points.value = response.points ?? [];
}

async function remove(cardId: number) {
  await api(`/cards/${cardId}`, { method: "DELETE" });
  cards.value = cards.value.filter((c) => c.id !== cardId);
  if (selectedCardId.value === cardId) selectedCardId.value = null;
}

async function toAlert(card: Card) {
  await router.push({
    path: "/alerts",
    query: { cardId: String(card.id), orgId: String(board.value?.orgId ?? "") },
  });
}

onMounted(load);
</script>
