/** 离线类型引用扫描：保留字符位置，屏蔽注释/字符串，不修改任何源码文件。 */
import fs from 'node:fs';
import path from 'node:path';

export function sourceFiles(root) {
  const walk = directory => fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(directory, entry.name);
    return entry.isDirectory() ? walk(file) : /\.(java|groovy)$/.test(file) ? [file] : [];
  });
  return ['backend/src', 'client-java/src', 'scripts'].flatMap(directory => walk(path.join(root, directory)));
}

export function codeOnly(source) {
  const chars = source.split('');
  const hide = (start, end) => {
    for (let i = start; i < end; i++) if (chars[i] !== '\n' && chars[i] !== '\r') chars[i] = ' ';
  };
  for (let i = 0; i < source.length;) {
    const start = i;
    if (source.startsWith('//', i)) {
      i = source.indexOf('\n', i);
      if (i < 0) i = source.length;
    } else if (source.startsWith('/*', i)) {
      const end = source.indexOf('*/', i + 2);
      i = end < 0 ? source.length : end + 2;
    } else if (source[i] === '"' || source[i] === "'") {
      const quote = source[i];
      const delimiter = source.startsWith(quote.repeat(3), i) ? quote.repeat(3) : quote;
      i += delimiter.length;
      while (i < source.length) {
        if (source[i] === '\\') i += 2;
        else if (source.startsWith(delimiter, i)) { i += delimiter.length; break; }
        else i++;
      }
    } else if (source.startsWith('$/', i)) {
      const end = source.indexOf('/$', i + 2);
      i = end < 0 ? source.length : end + 2;
    } else if (source[i] === '/' && /(?:[=~(,:\[]|\breturn)\s*$/.test(source.slice(0, i))) {
      // Groovy slashy 字符串；除法后没有类型包路径，不将其识别成字符串。
      i++;
      while (i < source.length) {
        if (source[i] === '\\') i += 2;
        else if (source[i++] === '/') break;
      }
    } else { i++; continue; }
    hide(start, Math.min(i, source.length));
  }
  return chars.join('');
}

export function packageTypes(sources) {
  const index = new Map();
  for (const source of sources) {
    const code = codeOnly(source);
    const packageName = code.match(/\bpackage\s+([\w.]+)/)?.[1] ?? '';
    let depth = 0;
    const scopes = [];
    let pending = null;
    for (const token of code.matchAll(/\b(?:class|interface|enum|record|trait)\s+(\w+)|[{}]/g)) {
      if (token[0] === '{') {
        depth++;
        if (pending) { scopes.push({ name: pending, depth }); pending = null; }
      } else if (token[0] === '}') {
        if (scopes.at(-1)?.depth === depth) scopes.pop();
        depth--;
      } else if (depth === 0 || scopes.at(-1)?.depth === depth) {
        const owner = [packageName, ...scopes.map(scope => scope.name)].filter(Boolean).join('.');
        const types = index.get(owner) ?? new Set();
        types.add(token[1]);
        index.set(owner, types);
        pending = token[1];
      }
    }
  }
  return index;
}

/** 只识别符合包/类型命名约定的引用；异常命名和完整类型解析由编译器 / IDEA 验证。 */
export function analyzeImports(source, index = new Map()) {
  let code = codeOnly(source);
  const packageName = code.match(/\bpackage\s+([\w.]+)/)?.[1] ?? '';
  const imports = [];
  const bindings = new Map();
  const bind = (name, type) => {
    const types = bindings.get(name) ?? new Set();
    types.add(type);
    bindings.set(name, types);
  };
  for (const match of code.matchAll(/\bimport\s+(static\s+)?([\w$]+(?:\.[\w$]+)*(?:\.\*)?)(?:\s+as\s+(\w+))?[\t ]*;?/g)) {
    const qualified = match[2];
    imports.push({ qualified, static: Boolean(match[1]), alias: match[3], index: match.index });
    if (!match[1] && !qualified.endsWith('.*')) bind(match[3] ?? qualified.split('.').at(-1), qualified);
    if (match[1] && /^[A-Z]/.test(qualified.split('.').at(-1))) bind(qualified.split('.').at(-1), qualified);
  }
  for (const type of index.get(packageName) ?? []) bind(type, packageName ? `${packageName}.${type}` : type);
  for (const match of code.matchAll(/\b(?:class|interface|enum|record|trait)\s+(\w+)/g)) {
    if (!(index.get(packageName)?.has(match[1]))) bind(match[1], `declaration:${match[1]}`);
  }
  // 类型参数与类同名时也会遮蔽 import；仅采用可确定的声明形态。
  for (const match of code.matchAll(/(?:\b(?:class|interface)\s+\w+|\b(?:public|protected|private|static)\s+)\s*<([^<>]+)>/g)) {
    for (const parameter of match[1].split(',')) {
      const name = parameter.trim().match(/^[A-Za-z_$][\w$]*/)?.[0];
      if (name) bind(name, `type-parameter:${name}`);
    }
  }
  // 声明语句中的包路径不参与扫描。
  code = code.replace(/\b(?:package|import)\s+[^\n;]+;?/g, match => match.replace(/[^\r\n]/g, ' '));
  const packageRoots = new Set(['java', 'javax', 'jakarta', 'org', 'com', 'net', 'io', 'cn', 'edu', 'lombok', 'spock', 'link',
    ...[...index.keys(), packageName, ...imports.map(item => item.qualified)].map(name => name.split('.')[0])]);
  const references = [...code.matchAll(/(?<![\w$.])(?:[a-z_$][a-z0-9_$]*\.)+[A-Z][\w$]*/g)]
    .filter(match => packageRoots.has(match[0].split('.')[0]) || /(?:@|\bnew\s+)\s*$/.test(code.slice(0, match.index)))
    .map(match => ({ qualified: match[0], simple: match[0].split('.').at(-1), index: match.index }));
  // 通配导入中的已知同名类型会占用简单名；避免与它们现有的引用发生冲突。
  for (const item of imports.filter(item => !item.static && item.qualified.endsWith('.*'))) {
    const owner = item.qualified.slice(0, -2);
    for (const type of index.get(owner) ?? []) {
      if (!bindings.has(type) && new RegExp(`(?<![\\w$.])${type}(?![\\w$])`).test(code)) bind(type, `${owner}.${type}`);
    }
  }
  const added = new Set();
  const edits = [];
  const exceptions = [];
  for (const reference of references) {
    const { qualified, simple } = reference;
    const alias = imports.find(item => item.qualified === qualified && item.alias)?.alias;
    const targets = bindings.get(alias ?? simple);
    if (targets && [...targets].some(target => target !== qualified)) {
      exceptions.push({ ...reference, conflicts: [...targets] });
      continue;
    }
    bind(alias ?? simple, qualified);
    edits.push({ ...reference, replacement: alias ?? simple });
    if (!alias && !imports.some(item => !item.static && item.qualified === qualified)
        && qualified.slice(0, -(simple.length + 1)) !== 'java.lang'
        && qualified.slice(0, -(simple.length + 1)) !== packageName) added.add(qualified);
  }
  return { edits, exceptions, imports: [...added].sort() };
}

export function shortenImports(source, analysis) {
  let result = source;
  for (const edit of [...analysis.edits].reverse()) {
    result = result.slice(0, edit.index) + edit.replacement + result.slice(edit.index + edit.qualified.length);
  }
  if (analysis.imports.length) {
    const eol = source.includes('\r\n') ? '\r\n' : '\n';
    const semicolon = /\bpackage\s+[\w.]+;|\bimport\s+[\w.]+;/.test(codeOnly(source))
      || !/\b(?:package|import)\s+/.test(codeOnly(source)) ? ';' : '';
    const declarations = [...codeOnly(result).matchAll(/^[\t ]*(?:package|import)\s+[^\r\n]+/gm)];
    const last = declarations.at(-1);
    const position = last ? last.index + last[0].length : 0;
    const block = analysis.imports.map(type => `import ${type}${semicolon}`).join(eol);
    result = result.slice(0, position) + (position ? eol : '') + block + (position ? '' : eol + eol) + result.slice(position);
  }
  return result;
}
