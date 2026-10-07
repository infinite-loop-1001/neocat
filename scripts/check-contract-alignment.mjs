#!/usr/bin/env node
/**
 * 前后端接口对齐核对（技术方案 03-api-contract.md）。
 *
 * 用途：用可执行的方式证明「前端 mock 的每个端点在后端都有对应实现」，
 *       以及「后端不存在一期明确排除的端点」。这是联调前的机械性证据，
 *       不依赖运行环境与任何中间件。
 *
 * 用法：node scripts/check-contract-alignment.mjs
 */

import { readFileSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const backendRoot = join(root, "backend/src/main/java/com/neocat");
const mockFile = join(root, "front/src/mock/router.ts");

// ── 后端端点 ────────────────────────────────────────────────
const backend = new Set();
function* httpControllers(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) yield* httpControllers(path);
    else if (entry.name.endsWith("Controller.java") && path.includes("/api/http/")) yield path;
  }
}
for (const file of httpControllers(backendRoot)) {
  const source = readFileSync(file, "utf8");
  const classPrefix = source.match(/@RequestMapping\("([^"]*)"\)/)?.[1] ?? "";
  // 支持两种写法：@GetMapping("/x") 与无参数的 @GetMapping
  const mappingRe = /@(Get|Post|Put|Delete)Mapping(?:\(\s*"([^"]*)")?/g;
  let match;
  while ((match = mappingRe.exec(source)) !== null) {
    const method = match[1].toUpperCase();
    const path = (classPrefix + (match[2] ?? "")).replace(/\/{2,}/g, "/") || "/";
    backend.add(`${method} ${path}`);
  }
}

// ── 前端 mock 端点 ──────────────────────────────────────────
const mock = readFileSync(mockFile, "utf8");
const frontend = new Set();

// 形式一：path === "/x" && method === "GET"（允许两者顺序互换）
const literalRe = /path === "([^"]+)"(?: && method === "([A-Z]+)")?/g;
let literal;
while ((literal = literalRe.exec(mock)) !== null) {
  const path = literal[1];
  const method = literal[2] ?? "GET";
  frontend.add(`${method} /api${path}`.replace(/\/{2,}/g, "/"));
}

// 形式二：正则路径，如 /^\/users\/\d+\/role$/ && method === "POST"
const regexRe = /(\/\^[^$]+\$\/)\s*&&\s*method === "([A-Z]+)"/g;
let regex;
while ((regex = regexRe.exec(mock)) !== null) {
  const pattern = regex[1]
    .replace(/^\/\^/, "")
    .replace(/\$\/$/, "")
    .replace(/\\\//g, "/")
    .replace(/\\d\+/g, "{id}")
    .replace(/\[\^\/\]\+/g, "{p}");
  frontend.add(`${regex[2]} /api${pattern}`.replace(/\/{2,}/g, "/"));
}

// ── 比较 ────────────────────────────────────────────────────
const normalize = (endpoint) =>
  endpoint.replace(/\{[a-zA-Z]+\}/g, "{}").replace(/\[[^\]]+\]/g, "{}");

const backendNormalized = new Set([...backend].map(normalize));

const missing = [...frontend].filter((ep) => {
  if (backendNormalized.has(normalize(ep))) return false;
  // 逐个后端端点比较，允许路径参数名不同
  return ![...backend].some((be) => {
    const [beMethod, bePath] = be.split(" ");
    const [feMethod, fePath] = ep.split(" ");
    if (beMethod !== feMethod) return false;
    const beSegments = bePath.split("/");
    const feSegments = fePath.split("/");
    if (beSegments.length !== feSegments.length) return false;
    return beSegments.every((seg, i) => seg.startsWith("{") || seg === feSegments[i]);
  });
});

// ── 一期明确排除的端点（若出现即为越界实现）────────────────
const FORBIDDEN = [
  { pattern: /^\/api\/alerts\/\{[^}]+\}\/ack$/, reason: "告警确认不属于一期（PRD 06 §2）" },
  { pattern: /^\/api\/alerts\/history$/, reason: "告警历史不属于一期（PRD 06 §11）" },
  { pattern: /^\/api\/overview/, reason: "应用总览不属于一期（PRD 00 §2）" },
  { pattern: /^\/api\/business/, reason: "Business 漏斗不属于一期（PRD 00 §2）" },
];
const violations = [];
for (const endpoint of backend) {
  const path = endpoint.split(" ")[1];
  for (const rule of FORBIDDEN) {
    if (rule.pattern.test(path)) {
      violations.push(`${endpoint} —— ${rule.reason}`);
    }
  }
}

// ── 输出 ────────────────────────────────────────────────────
console.log(`后端端点：${backend.size} 个`);
console.log(`前端 mock 端点：${frontend.size} 个`);
console.log();

let failed = false;

if (missing.length > 0) {
  failed = true;
  console.log("【缺失】前端调用但后端未实现：");
  missing.forEach((ep) => console.log(`  - ${ep}`));
  console.log();
}

if (violations.length > 0) {
  failed = true;
  console.log("【越界】后端存在一期明确排除的端点：");
  violations.forEach((v) => console.log(`  - ${v}`));
  console.log();
}

if (failed) {
  console.log("结果：契约未对齐");
  process.exit(1);
}

console.log("结果：契约对齐 —— 前端全部端点在后端均有实现，且无越界端点");
