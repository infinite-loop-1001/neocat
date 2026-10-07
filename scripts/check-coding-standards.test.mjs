import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

test('JDK 语法树检查覆盖注释、注解、多行字段与默认访问级别，不误报局部变量', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'neocat-standards-'));
  const helper = fileURLToPath(new URL('./CheckJavaStandards.java', import.meta.url));
  const run = source => {
    const file = path.join(dir, 'Fixture.java');
    fs.writeFileSync(file, source);
    return spawnSync('java', [helper, file], { encoding: 'utf8' });
  };
  try {
    const valid = run(`class Fixture {
      final Object a = new Object();

      // 注释与注解不影响真正的空行。
      @Deprecated final Object b = new Object();
      void run() { int a = 1; int b = 2; }
    }`);
    assert.equal(valid.status, 0, valid.stderr);
    const invalid = run(`import static java.util.stream.Collectors.toMap;
      class Fixture {
        final Object a =
          new Object();
        // 注释不代替空行
        @Deprecated Object b;
        java.util.Optional<String> find() { return null; }
        void run() { var m = list.stream().collect(toMap(x -> x.a, x -> x.b)); }
        record Nested(int id) {}
      }`);
    assert.equal(invalid.status, 1);
    for (const message of ['相邻成员字段', '重复 key', '禁止声明 record', '不返回 Optional']) {
      assert.ok(invalid.stderr.includes(message), invalid.stderr);
    }
    const collector = run(`class Fixture {
      void run() { var m = list.stream().collect(java.util.stream.Collectors.toMap(
        x -> pair(x.a, x.b), x -> x.value, (left, right) -> { throw new IllegalStateException(); })); }
    }`);
    assert.equal(collector.status, 0, collector.stderr);
    const syntaxError = run('class Fixture { broken java syntax here }');
    assert.equal(syntaxError.status, 1);
  } finally {
    // 仅清理此测试自行创建的精确临时目录。
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
