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

test('Java 判空检查覆盖左右比较、括号、三元与 lambda，忽略注释和字符串', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'neocat-null-standards-'));
  const helper = fileURLToPath(new URL('./CheckJavaStandards.java', import.meta.url));
  const run = source => {
    const file = path.join(dir, 'Fixture.java');
    fs.writeFileSync(file, source);
    return spawnSync('java', [helper, file], { encoding: 'utf8' });
  };
  try {
    const invalid = run(`class Fixture {
      boolean run(Object value) {
        boolean a = value == null;
        boolean b = value != null;
        boolean c = null == value;
        boolean d = null != value;
        boolean e = (value) == ((null));
        boolean f = (null) != value;
        String g = value == null ? "empty" : value.toString();
        java.util.function.Predicate<Object> h = x -> x != null && x.toString().isBlank();
        return value != null && value.toString().isEmpty();
      }
    }`);
    assert.equal(invalid.status, 1, invalid.stderr);
    assert.equal((invalid.stderr.match(/Java 判空必须使用/g) ?? []).length, 9, invalid.stderr);
    const valid = run(`import java.util.Objects;
      class Fixture {
        boolean run(Object value, Object other) {
          // value == null; null != value;
          /* ((null)) == value */
          String example = "value != null";
          String block = """
            null == value
            """;
          char literal = '=';
          java.util.function.Predicate<Object> nonNull = Objects::nonNull;
          java.util.function.Predicate<Object> isNull = Objects::isNull;
          Object required = Objects.requireNonNull(other);
          Object empty = null;
          return Objects.isNull(value) || Objects.nonNull(value) && value != other;
        }
      }`);
    assert.equal(valid.status, 0, valid.stderr);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('Objects 判空保持短路、三元默认值与副作用表达式单次求值', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'neocat-null-semantics-'));
  const file = path.join(dir, 'Fixture.java');
  try {
    fs.writeFileSync(file, `import java.util.Objects;
      class Fixture {
        private static int calls;

        static String next(String value) { calls++; return value; }
        static void check(boolean condition) {
          if (!condition) throw new AssertionError("null check semantics");
        }
        public static void main(String[] args) {
          String missing = null;
          check(Objects.isNull(missing));
          check(!Objects.nonNull(missing));
          check(Objects.nonNull("present"));
          check(!(Objects.nonNull(missing) && next("bad").isBlank()));
          check(Objects.isNull(missing) || next("bad").isBlank());
          check(calls == 0);
          String fallback = Objects.isNull(next(missing)) ? "fallback" : "present";
          check(fallback.equals("fallback") && calls == 1);
          check(Objects.nonNull(next("present")) && calls == 2);
          try {
            Objects.requireNonNull(missing);
            throw new AssertionError("required value did not fail");
          } catch (NullPointerException expected) { }
        }
      }`);
    const result = spawnSync('java', [file], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
