#!/usr/bin/env node
/** 离线源码检查：仅对可确定的规则报错；事务语义与 JSON 字段等价仍由规格/人工审计验证。 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const errors = [];
function files(dir, extension) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const name = path.join(dir, entry.name);
    return entry.isDirectory() ? files(name, extension) : name.endsWith(extension) ? [name] : [];
  });
}
// 保留换行和字符位置，去除注释/字符串，避免 Javadoc 中 record、SQL 等词误报。
function codeOnly(text) {
  return text.replace(/"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\/\*[\s\S]*?\*\/|\/\/[^\n]*/g,
    token => token.replace(/[^\n]/g, ' '));
}
function report(file, text, index, message) {
  errors.push(`${path.relative(root, file)}:${text.slice(0, index).split('\n').length} ${message}`);
}
const javaFiles = [
  ...['backend', 'client-java'].flatMap(module => files(path.join(root, module, 'src'), '.java')),
  ...files(path.join(root, 'scripts'), '.java'),
];
for (const file of javaFiles) {
  const text = fs.readFileSync(file, 'utf8');
  const code = codeOnly(text);
  for (const [pattern, message] of [
    [/\brecord\s+\w+\s*(?:<[^>]*>)?\s*\(/g, '禁止声明 record'],
    [/\b(?:cn\.hutool|com\.alibaba\.fastjson2?)\b/g, '禁止 Hutool / Fastjson'],
    [/@(?:lombok\.experimental\.)?UtilityClass\b/g, '不使用 @UtilityClass'],
  ]) {
    for (const match of code.matchAll(pattern)) report(file, text, match.index, message);
  }
  if (file.endsWith('Controller.java')) {
    if (/ResponseEntity\s*<\s*(?:Map|java\.util\.Map)\b/.test(code)) errors.push(`${path.relative(root, file)} Controller 禁止 Map 响应`);
    if (/public\s+(?:static\s+)?class\s+\w+(?:Request|Draft)\b/.test(code)) errors.push(`${path.relative(root, file)} 请求 DTO 必须移出 Controller`);
  }
}
// 使用 JDK 语法树，不依赖项目 classpath、不联网；不用正则猜测字段或 lambda 中的逗号。
const syntax = spawnSync('java', [path.join(root, 'scripts/CheckJavaStandards.java'), ...javaFiles],
  { cwd: root, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
if (syntax.status !== 0 || syntax.error) {
  errors.push(syntax.stderr?.trim() || syntax.error?.message || 'JDK 语法检查失败');
}
for (const module of ['backend', 'client-java']) {
  const pom = fs.readFileSync(path.join(root, module, 'pom.xml'), 'utf8').replace(/<!--[\s\S]*?-->/g, '');
  if (/hutool|fastjson/i.test(pom)) errors.push(`${module}/pom.xml 禁止 Hutool / Fastjson 依赖`);
  if (!/<parameters>\s*true\s*<\/parameters>|<arg>\s*-parameters\s*<\/arg>/.test(pom)) errors.push(`${module}/pom.xml 缺少 -parameters`);
}
if (errors.length) {
  console.error(errors.join('\n'));
  process.exitCode = 1;
} else {
  console.log(`编码规范检查通过：${javaFiles.length} 个手写 Java 文件，两个 POM；未扫描 target 生成代码。`);
}
