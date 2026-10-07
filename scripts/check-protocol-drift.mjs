#!/usr/bin/env node
/**
 * 上报协议漂移检查（技术方案 04-ingest-protocol.md §2.1）。
 *
 * 背景：本仓库不设独立协议模块，上报协议的**单一事实源**是仓库根的
 *       proto/neocat/ingest/v1/ingest.proto，backend 与 client-java 各自从它
 *       生成 Java 类。这个决定把「协议是否漂移」从人工约定变成可执行证据。
 *
 * 为什么必须检查：Protobuf 的字段号一旦两端不一致，**不报错**，而是静默
 *       解析错位，数据整体错乱。所以漂移必须被机械性地挡住。
 *
 * 用法：
 *   mvn -o -q -f backend/pom.xml -DskipTests package
 *   mvn -o -q -f client-java/pom.xml -DskipTests package
 *   node scripts/check-protocol-drift.mjs
 */

import { readFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

const protoSource = "proto/neocat/ingest/v1/ingest.proto";
const generated = [
  "backend/target/generated-sources/protobuf/java",
  "client-java/target/generated-sources/protobuf/java",
];

const PKG = "com/neocat/protocol/ingest/v1";

let failed = false;

// ── 1. 事实源必须唯一：仓库内不得存在第二份 .proto ──────────────
function findProtos(dir, acc = []) {
  for (const entry of readdirSync(dir)) {
    if (entry === "target" || entry === "node_modules" || entry === ".git") continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) findProtos(full, acc);
    else if (entry.endsWith(".proto")) acc.push(full.slice(root.length + 1));
  }
  return acc;
}

const protos = findProtos(root);
console.log(`协议源文件：`);
protos.forEach((p) => console.log(`  - ${p}`));
console.log();

if (!protos.includes(protoSource)) {
  failed = true;
  console.log(`【缺失】协议事实源不存在：${protoSource}`);
  console.log();
}

if (protos.length > 1) {
  failed = true;
  console.log("【重复】存在多份 .proto —— 方案 B 只允许一份事实源：");
  protos.filter((p) => p !== protoSource).forEach((p) => console.log(`  - ${p}`));
  console.log();
}

// ── 2. 两端生成物必须逐字节一致 ────────────────────────────────
const [beDir, sdkDir] = generated.map((g) => join(root, g, PKG));

for (const dir of [beDir, sdkDir]) {
  if (!existsSync(dir)) {
    failed = true;
    console.log(`【未生成】${dir.slice(root.length + 1)}`);
    console.log("  先分别执行：mvn -o -q -f backend/pom.xml -DskipTests package 与 mvn -o -q -f client-java/pom.xml -DskipTests package");
    console.log();
  }
}

if (!failed) {
  // 逐个生成类比对（排除服务端专属的 package-info，它是 backend 自己的源码）
  const files = readdirSync(beDir).filter((f) => f.endsWith(".java") && f !== "package-info.java");

  const mismatched = [];
  const sdkFiles = new Set(readdirSync(sdkDir));

  for (const file of files) {
    if (!sdkFiles.has(file)) {
      mismatched.push(`${file}（client-java 未生成）`);
      continue;
    }
    const be = readFileSync(join(beDir, file));
    const sdk = readFileSync(join(sdkDir, file));
    if (!be.equals(sdk)) mismatched.push(`${file}（内容不一致）`);
  }

  console.log(`比对生成类：${files.length} 个`);
  console.log();

  if (mismatched.length > 0) {
    failed = true;
    console.log("【漂移】两端生成物不一致：");
    mismatched.forEach((m) => console.log(`  - ${m}`));
    console.log();
  }
}

if (failed) {
  console.log("结果：协议检查未通过");
  process.exit(1);
}

console.log("结果：协议同源 —— 单一 .proto，两端生成物逐字节一致");
