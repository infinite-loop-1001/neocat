<template>
  <section class="workspace-panel">
    <header class="panel-head">
      <h2>平台配置</h2>
      <span class="status">慢阈值只影响后续分析，不回算历史</span>
    </header>

    <div class="platform-grid">
      <label class="field-label">平台时区</label>
      <input class="field" :value="platform.timezone" readonly />
      <p class="field-note">
        平台时区在初始化时设定，一期运行中不允许修改；所有自然周期、环比与告警分钟点共用该时区。
      </p>

      <label class="field-label" for="slowUrl">慢 URL 阈值（ms）</label>
      <input id="slowUrl" v-model.number="platform.slow.url" class="field" type="number" />

      <label class="field-label" for="slowSql">慢 SQL 阈值（ms）</label>
      <input id="slowSql" v-model.number="platform.slow.sql" class="field" type="number" />

      <label class="field-label" for="slowCall">慢调用阈值（ms）</label>
      <input id="slowCall" v-model.number="platform.slow.call" class="field" type="number" />

      <label class="field-label" for="slowCache">慢缓存阈值（ms）</label>
      <input id="slowCache" v-model.number="platform.slow.cache" class="field" type="number" />

      <button class="btn btn-primary" type="button" @click="saveSlow">保存慢阈值</button>

      <label class="field-label">通知通道</label>
      <div class="channel-row">
        <label class="channel-item"><input v-model="platform.channels.email" type="checkbox" /> 邮件</label>
        <label class="channel-item"><input v-model="platform.channels.dingtalk" type="checkbox" /> 钉钉</label>
        <label class="channel-item"><input v-model="platform.channels.feishu" type="checkbox" /> 飞书</label>
      </div>
      <button class="btn btn-primary" type="button" @click="saveChannels">保存通道配置</button>

      <p v-if="message" class="field-note" role="status">{{ message }}</p>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    </div>

    <p class="hint">未配置好的外部通道不可在告警规则中选择。</p>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, ApiError } from "../api";
import type { PlatformProfile } from "../mock/model";

const platform = ref<PlatformProfile>({
  timezone: "",
  initialized: true,
  slow: { url: 0, sql: 0, call: 0, cache: 0 },
  channels: { email: false, dingtalk: false, feishu: false },
});
const message = ref("");
const error = ref("");

async function load() {
  platform.value = await api<PlatformProfile>("/platform");
}

async function saveSlow() {
  error.value = "";
  message.value = "";
  try {
    platform.value = await api<PlatformProfile>("/platform/slow-thresholds", {
      method: "PUT",
      body: { slow: platform.value.slow },
    });
    message.value = "慢阈值已保存，只影响后续分析。";
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : "保存失败";
  }
}

async function saveChannels() {
  error.value = "";
  message.value = "";
  try {
    platform.value = await api<PlatformProfile>("/platform/channels", {
      method: "PUT",
      body: { channels: platform.value.channels },
    });
    message.value = "通道配置已保存。";
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : "保存失败";
  }
}

onMounted(load);
</script>
