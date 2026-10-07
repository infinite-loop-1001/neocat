<template>
  <div class="time-scope">
    <div class="scope-nav">
      <div class="scope-window-row">
        <button class="scope-arrow" type="button" aria-label="前一个时间窗口" @click="shift(-1)">◀</button>
        <span class="scope-window mono">{{ windowLabel }}</span>
        <button
          class="scope-arrow"
          type="button"
          aria-label="后一个时间窗口"
          :disabled="!canForward"
          :title="canForward ? '后一个时间窗口' : '已到当前时间窗口'"
          @click="shift(1)"
        >▶</button>
      </div>

      <div class="scope-step-row">
        <div class="scope-steps" role="group" aria-label="时间刻度">
          <button
            v-for="option in STEPS"
            :key="option.key"
            class="scope-step"
            :class="{ 'is-active': step === option.key }"
            type="button"
            :aria-pressed="step === option.key"
            :title="option.title"
            @click="setStep(option.key)"
          >
            {{ option.key }}
          </button>
        </div>
        <button class="scope-now" type="button" @click="goNow">回到现在</button>
      </div>
    </div>

    <div class="scope-quick">
      <label class="field-label" for="range">时间范围</label>
      <select id="range" v-model="quickProxy" class="select-field" @change="onQuick">
        <option value="__WINDOW__" disabled>自定义窗口（见上方导航）</option>
        <option value="CURRENT_HOUR">当前小时</option>
        <option value="RECENT_1H">最近 1 小时</option>
        <option value="RECENT_3H">最近 3 小时</option>
        <option value="RECENT_6H">最近 6 小时</option>
        <option value="RECENT_12H">最近 12 小时</option>
        <option value="RECENT_24H">最近 24 小时</option>
        <option value="TODAY">今天</option>
        <option value="THIS_WEEK">本周</option>
      </select>
      <span class="field-note">{{ secondsPerPoint }} 秒/点</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import {
  alignDay,
  alignHour,
  alignTo,
  alignWeek,
  isoMonth,
  initialTimeSelection,
  quickTimeBounds,
  rangeAt,
  stepForward,
  type TimeStep,
} from "../api/time-range";

/**
 * 时间范围控件（PRD 03 §2、技术方案 03 §4.2）。
 *
 * 两种方式并存，共同决定同一个 `range`：
 * 1. **窗口导航**：显示当前窗口、◀ ▶ 前后翻页、h/w/m 切换刻度（1 小时 / 1 周 / 1 月）、回到现在；
 * 2. **快捷范围**：当前小时、最近 1/3/6/12/24 小时、今天、本周这类窗口。
 *
 * 默认是**当前自然小时**（整点对齐），不是从当前时刻往回滚的「最近 1 小时」：
 * 滚动窗口的区间带零头（18:59–19:59），既不好读，也无法与整点的历史窗口直接比较。
 *
 * 窗口一律按自然边界对齐（整点 / 周一 / 月初），翻页因此是幂等的。
 * 对齐与默认范围取自 `api/time-range`，与各报表页的初始 `range` 同一份定义——
 * 两边各算一次的话，控件显示 19:00–20:00、页面却按别的范围取数，数字会对不上。
 */

const FALLBACK_BUCKET = 600;

const props = defineProps<{ bucketSeconds?: number; initialRange?: string }>();
const emit = defineEmits<{ change: [{ range: string; bucketSeconds: number }] }>();

const STEPS: { key: TimeStep; title: string }[] = [
  { key: "h", title: "按小时：窗口 1 小时，粒度 1 分钟" },
  { key: "w", title: "按周：窗口 1 自然周，粒度 1 小时" },
  { key: "m", title: "按月：窗口 1 自然月，粒度 1 天" },
];

/** 固定快捷范围对应的默认粒度（PRD 03 §2.2）。 */
const GRANULARITY: Record<string, number> = {
  CURRENT_HOUR: 60,
  RECENT_1H: 60,
  RECENT_3H: 300,
  RECENT_6H: 600,
  RECENT_12H: 1200,
  RECENT_24H: 3600,
  TODAY: 600,
  THIS_WEEK: 3600,
};

const HOUR_MS = 3_600_000;

const initial = props.initialRange ? initialTimeSelection(props.initialRange, Date.now()) : undefined;
const quick = ref(initial?.quick ?? "CURRENT_HOUR");
const step = ref<TimeStep>(initial?.step ?? "h");
/** 当前窗口起点（已按 step 对齐）。 */
const windowStart = ref(initial?.start ?? alignHour(Date.now()));
/** 快捷范围的查询时刻：标签据此计算窗口，不对窗口做实时跳动。 */
const queriedAt = ref(Date.now());
/** quick = 跟随快捷范围；window = 跟随窗口导航。 */
const mode = ref<"quick" | "window">(initial?.mode ?? "quick");
/** 单独刷新导航边界，不改变快捷范围已查询的时间或触发请求。 */
const navigationNow = ref(Date.now());
let clockTimer: ReturnType<typeof setInterval> | undefined;
onMounted(() => {
  clockTimer = setInterval(() => { navigationNow.value = Date.now(); }, 1000);
});
onUnmounted(() => { clearInterval(clockTimer); });

/**
 * 快捷范围下 ▶ 始终可用：点它是「进入当前窗口」，不是跳到未来。
 * 窗口模式下只有后面还有真实窗口时才可前进。
 */
const canForward = computed(() =>
  mode.value === "quick" || stepForward(windowStart.value, step.value, 1) <= navigationNow.value
);

/**
 * 下拉框的显示值。
 *
 * 窗口导航生效时回调一个占位项，避免下拉框仍显示「当前小时」而图上其实是历史窗口；
 * 该占位项不可选（disabled），用户任选一项即切回快捷范围。
 */
const quickProxy = computed({
  get: () => (mode.value === "window" ? "__WINDOW__" : quick.value),
  set: (value: string) => {
    if (value !== "__WINDOW__") quick.value = value;
  },
});

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

function clock(ms: number): string {
  const d = new Date(ms);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function dateOnly(ms: number): string {
  const d = new Date(ms);
  return `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())}`;
}

/** 当前生效窗口的左闭右开区间。 */
const windowBounds = computed(() => {
  if (mode.value === "quick") {
    return quickTimeBounds(quick.value, queriedAt.value);
  }

  const from = windowStart.value;
  // 右端就是下一个自然周期的起点，不需要另算一次月末天数
  return { from, to: stepForward(from, step.value, 1) };
});

const windowLabel = computed(() => {
  const { from, to } = windowBounds.value;

  if (mode.value === "window" && step.value === "m") return isoMonth(from).replace("-", "/");

  const sameDay = alignDay(from) === alignDay(to - 1);
  if (sameDay) {
    const today = alignDay(Date.now()) === alignDay(from);
    // 当天窗口只显示时刻（「现在」一眼可见）；跨天时补上日期
    return today ? `${clock(from)} – ${clock(to)}` : `${dateOnly(from)} ${clock(from)} – ${clock(to)}`;
  }
  return `${dateOnly(from)} – ${dateOnly(to - 1)}`;
});

const secondsPerPoint = computed(() => current().bucketSeconds);

/** 当前应当发给后端的 range 与对应粒度。 */
function current(): { range: string; bucketSeconds: number } {
  if (mode.value === "quick") {
    // 「当前小时」是整点对齐的固定窗口，不是一个可以直接发出去的字符串
    if (quick.value === "CURRENT_HOUR") return rangeAt(alignHour(queriedAt.value), "h");
    const fallback = props.bucketSeconds ?? FALLBACK_BUCKET;
    return { range: quick.value, bucketSeconds: GRANULARITY[quick.value] ?? fallback };
  }
  return rangeAt(windowStart.value, step.value);
}

function emitChange() {
  emit("change", current());
}

function onQuick() {
  mode.value = "quick";
  queriedAt.value = Date.now();
  emitChange();
}

/**
 * 前后翻页。
 *
 * ▶ 从快捷范围切过来时落到「当前窗口」而不往前偏移，避免跳到未来窗口；
 * ◀ 则必须真的往前一个窗口——默认快捷范围本身就是当前整点小时，
 * 若也只做「对齐到当前窗口」，点 ◀ 会毫无反应。
 */
function shift(direction: number) {
  navigationNow.value = Date.now();
  if (mode.value === "quick") {
    const aligned = alignTo(navigationNow.value, step.value);
    windowStart.value = direction < 0 ? stepForward(aligned, step.value, -1) : aligned;
    mode.value = "window";
    emitChange();
    return;
  }

  const target = stepForward(windowStart.value, step.value, direction);
  if (direction > 0 && target > navigationNow.value) return;
  windowStart.value = target;

  emitChange();
}

function setStep(next: TimeStep) {
  navigationNow.value = Date.now();
  step.value = next;
  // 换刻度时把当前窗口起点对齐到新刻度的自然边界
  windowStart.value = alignTo(mode.value === "window" ? windowStart.value : Date.now(), next);
  mode.value = "window";
  emitChange();
}

function goNow() {
  navigationNow.value = Date.now();
  windowStart.value = alignTo(navigationNow.value, step.value);
  mode.value = "window";
  emitChange();
}
</script>
