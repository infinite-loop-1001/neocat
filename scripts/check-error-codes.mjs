import { readFileSync } from "node:fs";

const backend = readFileSync("backend/src/main/java/com/neocat/common/error/ErrorCode.java", "utf8");
const frontend = readFileSync("front/src/api/error-codes.ts", "utf8");
const javaEntries = [...backend.matchAll(/^\s*([A-Z][A-Z_]+)\((\d+), "/gm)];
const tsEntries = [...frontend.matchAll(/^\s*([A-Z][A-Z_]+): (\d+),?$/gm)];
const java = new Map(javaEntries.map(([, name, code]) => [name, Number(code)]));
const ts = new Map(tsEntries.map(([, name, code]) => [name, Number(code)]));
if (java.size === 0 || ts.size === 0 || java.size !== javaEntries.length || ts.size !== tsEntries.length) {
  throw new Error("无法解析或发现重复的错误码定义");
}
for (const [name, code] of java) {
  if (ts.get(name) !== code) throw new Error(`${name}: 后端 ${code}，前端 ${ts.get(name)}`);
}
for (const name of ts.keys()) {
  if (!java.has(name)) throw new Error(`前端多余错误码：${name}`);
}
console.log(`错误码契约对齐：${java.size} 个后端编号与前端编号一致`);
