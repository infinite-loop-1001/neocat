<template>
  <section class="workspace-panel">
    <header class="panel-head">
      <h2>告警规则</h2>
      <span class="status">一期不提供告警历史、确认或严重度</span>
    </header>

    <p class="hint">
      规则保存后<strong>始终为关闭</strong>，必须手动启用。启用后只使用启用时刻之后的完整分钟点建立窗口；
      编辑保存会再次关闭并清零窗口。缺口点会打断窗口。
    </p>

    <div class="alert-form">
      <label class="field-label" for="scope">作用范围</label>
      <select id="scope" v-model="draft.scope" class="select-field">
        <option value="SERVICE">服务告警（任意登录用户可配）</option>
        <option value="ORGANIZATION">组织告警（仅叶子有效成员）</option>
      </select>

      <label v-if="draft.scope === 'ORGANIZATION'" class="field-label" for="org">叶子组织</label>
      <select v-if="draft.scope === 'ORGANIZATION'" id="org" v-model.number="draft.orgId" class="select-field">
        <option v-for="org in leafOrgs" :key="org.id" :value="org.id">{{ org.name }}</option>
      </select>

      <label class="field-label" for="service">目标服务</label>
      <select id="service" v-model="draft.service" class="select-field">
        <option v-for="s in services" :key="s.name" :value="s.name">{{ s.name }}</option>
      </select>

      <label class="field-label" for="stat">统计项</label>
      <input id="stat" v-model="draft.stat" class="field mono" placeholder="例如 FAILURE_RATE" />

      <label class="field-label" for="comparator">比较</label>
      <div class="threshold-row">
        <select id="comparator" v-model="draft.comparator" class="select-field">
          <option value="GT">大于</option>
          <option value="GTE">大于等于</option>
          <option value="LT">小于</option>
          <option value="LTE">小于等于</option>
        </select>
        <input v-model.number="draft.threshold" class="field" type="number" step="0.01" />
      </div>

      <label class="field-label" for="combinator">条件连接符（整条规则统一）</label>
      <select id="combinator" v-model="draft.combinator" class="select-field">
        <option value="AND">AND（每点所有条件都满足）</option>
        <option value="OR">OR（每点至少一个条件满足）</option>
      </select>

      <label class="field-label" for="window">连续点数 X（整条规则共用）</label>
      <input id="window" v-model.number="draft.windowPoints" class="field" type="number" min="1" max="1440" />

      <label class="field-label" for="channels">通知通道</label>
      <div class="channel-row">
        <label v-for="channel in channelOptions" :key="channel.value" class="channel-item">
          <input
            type="checkbox"
            :value="channel.value"
            :checked="draft.channels.includes(channel.value)"
            :disabled="!channel.available"
            @change="toggleChannel(channel.value)"
          />
          <span :class="{ disabled: !channel.available }">{{ channel.label }}</span>
          <span v-if="!channel.available" class="field-note">未配置</span>
        </label>
      </div>

      <div class="alert-actions">
        <button class="btn btn-ghost" type="button" @click="preview">预览（试算）</button>
        <button class="btn btn-ghost" type="button" @click="previewNext">切换试算场景</button>
        <button class="btn btn-primary" type="button" @click="save">保存（保存后为关闭）</button>
      </div>

      <p v-if="message" class="field-note" role="status">{{ message }}</p>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    </div>

    <section v-if="previewResult" class="preview-panel" :class="previewClass">
      <header class="panel-head">
        <h3>预告警</h3>
        <span class="status">{{ previewLabel }}</span>
      </header>
      <ul class="preview-points">
        <li v-for="point in previewResult.points" :key="point.minute">
          <span class="mono">{{ formatTime(point.minute) }}</span>
          <span v-if="!point.known" class="status danger">数据不足（缺数不当 0）</span>
          <span v-else-if="point.satisfied" class="status success">满足</span>
          <span v-else class="status">不满足</span>
        </li>
      </ul>
      <p class="hint">预告警只展示，不发送通知、不保存历史、不改变规则状态。</p>
    </section>

    <table class="data-table">
      <thead>
        <tr>
          <th>规则</th>
          <th>范围</th>
          <th class="num">X</th>
          <th>连接</th>
          <th>状态</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="rule in rules" :key="rule.id">
          <td class="strong">
            {{ rule.name }}
            <span v-if="rule.invalid" class="status danger">目标已失效（保留配置）</span>
          </td>
          <td>{{ rule.scope === "SERVICE" ? "服务" : `组织 #${rule.orgId}` }}</td>
          <td class="num">{{ rule.windowPoints }}</td>
          <td class="mono">{{ rule.combinator }}</td>
          <td>
            <span class="status" :class="rule.enabled ? 'success' : ''">{{ rule.enabled ? "已启用" : "已关闭" }}</span>
          </td>
          <td class="actions">
            <button v-if="!rule.enabled" class="btn btn-ghost" type="button" @click="enable(rule.id)">启用</button>
            <button v-else class="btn btn-ghost" type="button" @click="disable(rule.id)">关闭</button>
            <button class="btn btn-ghost" type="button" @click="remove(rule.id)">删除</button>
          </td>
        </tr>
        <tr v-if="!rules.length">
          <td colspan="6" class="empty">还没有告警规则</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import { api, ApiError } from "../api";
import type { AlertRule, OrgNode } from "../mock/model";

const route = useRoute();

interface PreviewResult {
  result: string;
  points: { minute: number; known: boolean; satisfied: boolean }[];
}

const rules = ref<AlertRule[]>([]);
const services = ref<{ name: string }[]>([]);
const leafOrgs = ref<OrgNode[]>([]);
const previewResult = ref<PreviewResult | null>(null);
const message = ref("");
const error = ref("");

const draft = ref({
  scope: "SERVICE" as "SERVICE" | "ORGANIZATION",
  orgId: null as number | null,
  service: "order",
  stat: "FAILURE_RATE",
  comparator: "GT",
  threshold: 0.05,
  combinator: "AND" as "AND" | "OR",
  windowPoints: 3,
  channels: ["EMAIL"] as string[],
});

const channelOptions = ref([
  { value: "EMAIL", label: "邮件", available: true },
  { value: "DINGTALK", label: "钉钉", available: false },
  { value: "FEISHU", label: "飞书", available: false },
]);

const previewLabel = computed(() => {
  switch (previewResult.value?.result) {
    case "TRIGGER":
      return "当前会触发";
    case "NO_TRIGGER":
      return "当前不会触发";
    case "INSUFFICIENT_DATA":
      return "数据不足";
    default:
      return "";
  }
});

const previewClass = computed(() => {
  switch (previewResult.value?.result) {
    case "TRIGGER":
      return "is-trigger";
    case "INSUFFICIENT_DATA":
      return "is-insufficient";
    default:
      return "is-clear";
  }
});

function formatTime(ms: number): string {
  return new Date(ms).toLocaleString("zh-CN", { hour12: false });
}

function toggleChannel(value: string) {
  draft.value.channels = draft.value.channels.includes(value)
    ? draft.value.channels.filter((c) => c !== value)
    : [...draft.value.channels, value];
}

async function load() {
  rules.value = await api<AlertRule[]>("/alerts");
  services.value = await api<{ name: string }[]>("/services");
  const all = await api<OrgNode[]>("/orgs");
  const mine = await api<number[]>("/orgs/mine");
  leafOrgs.value = all.filter((o) => o.leaf && mine.includes(o.id));
  if (draft.value.orgId === null && leafOrgs.value.length) {
    draft.value.orgId = leafOrgs.value[0].id;
  }
  const profile = await api<{ channels: { email: boolean; dingtalk: boolean; feishu: boolean } }>("/platform");
  channelOptions.value[0].available = profile.channels.email;
  channelOptions.value[1].available = profile.channels.dingtalk;
  channelOptions.value[2].available = profile.channels.feishu;
  if (!profile.channels.email) draft.value.channels = [];
}

/**
 * 试算三态。
 *
 * 「切换试算场景」按钮用于逐个验证三态展示；真实后端会按实际数据返回其中之一，
 * 因此前端只负责呈现，不自行推断结果。
 */
const SCENARIOS = ["TRIGGER", "NO_TRIGGER", "INSUFFICIENT_DATA"] as const;
const scenarioIndex = ref(0);

async function preview() {
  error.value = "";
  message.value = "";
  const variant = SCENARIOS[scenarioIndex.value % SCENARIOS.length];
  previewResult.value = await api<PreviewResult>("/alerts/preview", {
    method: "POST",
    body: {
      stat: draft.value.stat,
      comparator: draft.value.comparator,
      threshold: draft.value.threshold,
      combinator: draft.value.combinator,
      windowPoints: draft.value.windowPoints,
      scenario: variant,
    },
  });
}

async function previewNext() {
  scenarioIndex.value = (scenarioIndex.value + 1) % SCENARIOS.length;
  await preview();
}

async function save() {
  error.value = "";
  message.value = "";
  try {
    await api("/alerts", {
      method: "POST",
      body: {
        scope: draft.value.scope,
        orgId: draft.value.scope === "ORGANIZATION" ? draft.value.orgId : null,
        name: `${draft.value.service} ${draft.value.stat}`,
        target: {
          kind: "RAW_METRIC",
          cardId: 0,
          service: draft.value.service,
          reportKind: "TRANSACTION",
          targetType: "URL",
          targetName: "POST /orders",
        },
        combinator: draft.value.combinator,
        windowPoints: draft.value.windowPoints,
        conditions: [
          {
            stat: draft.value.stat,
            comparator: draft.value.comparator,
            threshold: draft.value.threshold,
          },
        ],
        recipients: [3],
        channels: draft.value.channels,
      },
    });
    message.value = "已保存为关闭状态，请手动启用。";
    await load();
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : "保存失败";
  }
}

async function enable(id: number) {
  await api(`/alerts/${id}/enable`, { method: "POST" });
  await load();
}

async function disable(id: number) {
  await api(`/alerts/${id}/disable`, { method: "POST" });
  await load();
}

async function remove(id: number) {
  await api(`/alerts/${id}`, { method: "DELETE" });
  await load();
}

onMounted(() => {
  // 从卡片一键创建时目标与公式已复制，用户只需补充条件、窗口与收件人（PRD 05 §8）
  if (route.query.cardId) {
    draft.value.scope = "ORGANIZATION";
    message.value = "已从卡片复制目标与公式，请补充条件、窗口与收件人。";
  }
  void load();
});
</script>
