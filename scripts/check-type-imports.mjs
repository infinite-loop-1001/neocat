#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { analyzeImports, packageTypes, sourceFiles } from './type-imports.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const files = sourceFiles(root);
const sources = files.map(file => fs.readFileSync(file, 'utf8'));
const index = packageTypes(sources);
let violations = 0;
let exceptions = 0;
for (let i = 0; i < files.length; i++) {
  const analysis = analyzeImports(sources[i], index);
  exceptions += analysis.exceptions.length;
  for (const edit of analysis.edits) {
    violations++;
    console.error(`${path.relative(root, files[i])}:${sources[i].slice(0, edit.index).split('\n').length} 类型引用使用 import + 简单类名：${edit.qualified}`);
  }
}
if (violations) process.exitCode = 1;
else console.log(`类型引用检查通过：${files.length} 个 Java / Groovy 文件，${exceptions} 处同名冲突保留全限定名。`);
