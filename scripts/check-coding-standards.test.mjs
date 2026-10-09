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
        Optional<String> find() { return null; }
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
        function.Predicate<Object> h = x -> x != null && x.toString().isBlank();
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
          function.Predicate<Object> nonNull = Objects::nonNull;
          function.Predicate<Object> isNull = Objects::isNull;
          Object required = Objects.requireNonNull(other);
          Object empty = null;
           return Objects.isNull(value) || Objects.nonNull(value) && !Objects.equals(value, other);
        }
      }`);
    assert.equal(valid.status, 0, valid.stderr);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('容器判空必须走 CollectionUtils / MapUtils，且不误报字符串 isEmpty', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'neocat-container-standards-'));
  const helper = fileURLToPath(new URL('./CheckJavaStandards.java', import.meta.url));
  const run = source => {
    const file = path.join(dir, 'Fixture.java');
    fs.writeFileSync(file, source);
    return spawnSync('java', [helper, file], { encoding: 'utf8' });
  };
  try {
    const invalid = run(`import java.util.Objects;
      class Fixture {
      boolean run(List<String> list, Map<String, String> map, Set<String> set) {
        boolean a = Objects.isNull(list) || list.isEmpty();
        boolean b = Objects.isNull(map) || map.isEmpty();
        boolean c = Objects.nonNull(set) && !set.isEmpty();
        boolean d = list.size() == 0;
        return a || b || c || d;
      }
    }`);
    assert.equal(invalid.status, 1, invalid.stderr);
    assert.equal((invalid.stderr.match(/容器判空必须使用/g) ?? []).length, 4, invalid.stderr);
    assert.ok(!invalid.stderr.includes('禁止直接比较 null'), invalid.stderr);

    const valid = run(`import org.apache.commons.collections4.CollectionUtils;
      import org.apache.commons.collections4.MapUtils;
      import java.util.Objects;
      class Fixture {
        boolean run(List<String> list, Map<String, String> map, String text) {
          // String.isEmpty 与容器判空规则无关。
          if (Objects.nonNull(text) && !text.isEmpty()) return false;
          return CollectionUtils.isEmpty(list) || MapUtils.isEmpty(map)
              || CollectionUtils.isNotEmpty(list) || MapUtils.isNotEmpty(map);
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

test('生产时间入口与领域配置归属检查，仅允许公共实现及测试时钟', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'neocat-time-standards-'));
  const helper = fileURLToPath(new URL('./CheckJavaStandards.java', import.meta.url));
  const run = (relative, source) => {
    const file = path.join(dir, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, source);
    return spawnSync('java', [helper, file], { encoding: 'utf8' });
  };
  try {
    const invalid = run('backend/src/main/java/com/neocat/common/Fixture.java', `package com.neocat.common;
      class Fixture {
        @ApolloStaticValue("key") public static volatile int VALUE;

        private final java.time.Clock clock;
        Fixture(java.time.Clock clock) { this.clock = clock; }
        java.time.Clock bean() { return java.time.Clock.systemUTC(); }
        void run() { java.time.Instant.now(); System.currentTimeMillis(); }
      }`);
    assert.equal(invalid.status, 1, invalid.stderr);
    for (const message of ['禁止持有或注入 Clock', '禁止提供 Clock Bean', '禁止直接读取系统墙上时间', '领域动态配置禁止放入 common']) {
      assert.ok(invalid.stderr.includes(message), invalid.stderr);
    }
    const validSource = `class Fixture {
      void run() { java.time.Instant value = TimeProvider.now(); System.nanoTime(); }
    }`;
    assert.equal(run('backend/src/main/java/com/neocat/alert/Fixture.java', validSource).status, 0);
    const implementation = run('backend/src/main/java/com/neocat/common/time/clock/TimeProvider.java',
      'package com.neocat.common.time.clock; class TimeProvider { java.time.Clock clock = java.time.Clock.systemUTC(); }');
    assert.equal(implementation.status, 0, implementation.stderr);
    const testClock = run('backend/src/test/java/Fixture.java', 'class Fixture { java.time.Clock clock = java.time.Clock.systemUTC(); }');
    assert.equal(testClock.status, 0, testClock.stderr);
    const config = run('backend/src/main/java/com/neocat/alert/config/Fixture.java',
      'package com.neocat.alert.config; class Fixture { @ApolloStaticValue("key") public static volatile int VALUE; }');
    assert.equal(config.status, 0, config.stderr);
    const staticCalls = run('backend/src/main/java/com/neocat/alert/Fixture.java', `
      import static java.time.Instant.now;
      import static java.lang.System.*;
      import static java.time.Clock.*;
      class Fixture {
        void run() {
          now(); currentTimeMillis(); systemUTC();
          java.util.function.Supplier<java.time.Instant> reference = java.time.Instant::now;
          java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        }
      }`);
    assert.equal(staticCalls.status, 1, staticCalls.stderr);
    assert.equal((staticCalls.stderr.match(/禁止直接读取系统墙上时间/g) ?? []).length, 5, staticCalls.stderr);
    const rounded = run('backend/src/main/java/com/neocat/alert/Fixture.java', `
      class Fixture {
        long minute() { return TimeProvider.now().minusSeconds(5).toEpochMilli() / 60_000L * 60_000L; }
        long other() { return TimeProvider.millis() / 60_000L; }
      }`);
    assert.equal(rounded.status, 1, rounded.stderr);
    assert.equal((rounded.stderr.match(/分钟点对齐禁止手写毫秒取整/g) ?? []).length, 2, rounded.stderr);
    const aligned = run('backend/src/main/java/com/neocat/alert/Fixture.java',
      'class Fixture { long minute() { return TimeProvider.delayedMinuteStart(5).toEpochMilli(); } }');
    assert.equal(aligned.status, 0, aligned.stderr);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('接口文档注解完整性：Controller 需 @Tag、端点需 @Operation、DTO 需 @Schema', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'neocat-api-docs-standards-'));
  const helper = fileURLToPath(new URL('./CheckJavaStandards.java', import.meta.url));
  const write = (relative, source) => {
    const file = path.join(dir, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, source);
    return file;
  };
  const run = files => spawnSync('java', [helper, ...files], { encoding: 'utf8' });
  const controller = (name, tag, operation) => `package com.neocat.demo.api.http;
      import io.swagger.v3.oas.annotations.Operation;
      import io.swagger.v3.oas.annotations.tags.Tag;
      ${tag}
      @org.springframework.web.bind.annotation.RestController
      class ${name} {
        ${operation}
        @org.springframework.web.bind.annotation.GetMapping("/x")
        org.springframework.http.ResponseEntity<String> list() { return null; }
      }`;
  try {
    const missing = run([write('backend/src/main/java/com/neocat/demo/api/http/DemoController.java',
      controller('DemoController', '', ''))]);
    assert.equal(missing.status, 1, missing.stderr);
    assert.ok(missing.stderr.includes('对外 Controller 必须有 @Tag'), missing.stderr);
    assert.ok(missing.stderr.includes('对外端点必须有 @Operation'), missing.stderr);

    const documented = run([write('backend/src/main/java/com/neocat/demo/api/http/DemoController.java',
      controller('DemoController', '@Tag(name = "演示", description = "演示接口")',
        '@Operation(summary = "查询演示", operationId = "demoList")'))]);
    assert.equal(documented.status, 0, documented.stderr);

    const blankTag = run([write('backend/src/main/java/com/neocat/demo/api/http/DemoController.java',
      controller('DemoController', '@Tag(name = " ", description = "x")',
        '@Operation(summary = "查询演示", operationId = "demoList")'))]);
    assert.equal(blankTag.status, 1, blankTag.stderr);
    assert.ok(blankTag.stderr.includes('@Tag 的 name 与 description 不能为空'), blankTag.stderr);

    const noDtoSchema = run([write('backend/src/main/java/com/neocat/demo/api/http/dto/Draft.java',
      'package com.neocat.demo.api.http.dto; class Draft { String name; }')]);
    assert.equal(noDtoSchema.status, 1, noDtoSchema.stderr);
    assert.ok(noDtoSchema.stderr.includes('HTTP DTO 类必须有 @Schema'), noDtoSchema.stderr);

    const duplicated = run([
      write('backend/src/main/java/com/neocat/demo/api/http/DemoController.java',
        controller('DemoController', '@Tag(name = "演示", description = "演示接口")',
          '@Operation(summary = "查询演示", operationId = "demoList")')),
      write('backend/src/main/java/com/neocat/demo/api/http/Dup2Controller.java',
        controller('Dup2Controller', '@Tag(name = "演示2", description = "演示接口2")',
          '@Operation(summary = "重复", operationId = "demoList")'))]);
    assert.equal(duplicated.status, 1, duplicated.stderr);
    assert.ok(duplicated.stderr.includes('operationId 必须全局唯一'), duplicated.stderr);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('具名生产类型独立文件：覆盖 DTO、接口隐式 public、枚举与约定除外项', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'neocat-type-file-standards-'));
  const helper = fileURLToPath(new URL('./CheckJavaStandards.java', import.meta.url));
  const run = (relative, source) => {
    const file = path.join(dir, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, source);
    return spawnSync('java', [helper, file], { encoding: 'utf8' });
  };
  try {
    const invalid = run('backend/src/main/java/sample/Fixture.java', `package sample;
      class Fixture {
        public static class Result {}
        interface Port { class Response {} }
        enum State { ON, OFF }
        class PackageHelper {}
      }
      class Another {}`);
    assert.equal(invalid.status, 1, invalid.stderr);
    assert.equal((invalid.stderr.match(/具名生产类型必须拆/g) ?? []).length, 5, invalid.stderr);
    assert.ok(invalid.stderr.includes('顶层类型名必须与独立 Java 文件名一致'), invalid.stderr);
    const valid = run('backend/src/main/java/sample/Fixture.java', `package sample;
      class Fixture {
        private static class Helper { class Implementation {} }
        void run() { class LocalHelper {} }
        Object helper() { return new Object() { class AnonymousHelper {} }; }
      }`);
    assert.equal(valid.status, 0, valid.stderr);
    const builder = run('client-java/src/main/java/com/neocat/client/NeoCat.java',
      'package com.neocat.client; class NeoCat { public static class Builder { class State {} } }');
    assert.equal(builder.status, 0, builder.stderr);
    const sdk = run('client-java/src/main/java/com/neocat/client/NeoCat.java',
      'package com.neocat.client; class NeoCat { public static class RemoteCallHandle {} }');
    assert.equal(sdk.status, 1, sdk.stderr);
    const fakeBuilder = run('backend/src/main/java/sample/Fixture.java',
      'package sample; class Fixture { public static class Builder {} }');
    assert.equal(fakeBuilder.status, 1, fakeBuilder.stderr);
    const testFixture = run('backend/src/test/java/sample/Fixture.java',
      'package sample; class Fixture { public static class Result {} }');
    assert.equal(testFixture.status, 0, testFixture.stderr);
    const generated = run('backend/target/generated-sources/sample/Fixture.java',
      'package sample; class Fixture { public static class Result {} }');
    assert.equal(generated.status, 0, generated.stderr);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('空 JDK 容器工厂与 subList 禁止使用', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'neocat-collections-standards-'));
  const helper = fileURLToPath(new URL('./CheckJavaStandards.java', import.meta.url));
  const file = path.join(dir, 'Fixture.java');
  try {
    fs.writeFileSync(file, `class Fixture {
      void run(List<String> values) {
        List<String> a = List.of();
        Set<String> b = Set.of();
        Map<String, String> c = Map.of();
        List<String> d = List.<String>of();
        ${['values.', 'sub', 'List(0, 1);'].join('')}
      }
    }`);
    const result = spawnSync('java', [helper, file], { encoding: 'utf8' });
    assert.equal(result.status, 1, result.stderr);
    assert.equal((result.stderr.match(/空容器必须使用/g) ?? []).length, 4, result.stderr);
    assert.equal((result.stderr.match(/源码中禁止使用 subList/g) ?? []).length, 1, result.stderr);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('枚举与对象相等必须使用 Objects.equals，原始类型常量不误报', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'neocat-enum-equals-standards-'));
  const helper = fileURLToPath(new URL('./CheckJavaStandards.java', import.meta.url));
  const run = source => {
    const file = path.join(dir, 'Fixture.java');
    fs.writeFileSync(file, source);
    return spawnSync('java', [helper, file], { encoding: 'utf8' });
  };
  try {
    const invalid = run(`enum AggregationLevel { DAY, HOUR, WEEK }
      enum NodeKind { EVENT }
      class Fixture {
      boolean run(AggregationLevel level, Node node, String text) {
        boolean a = level == AggregationLevel.DAY;
        boolean b = level != AggregationLevel.HOUR;
        boolean c = node.getKind() != NodeKind.EVENT;
        boolean d = (AggregationLevel.WEEK) == (level);
        boolean e = node.level() == level;
        return a || b || c || d || e;
      }
      enum Unit { COUNT, NUMBER;
        boolean same(Unit other) { return this == other; }
        boolean self() { return this == NUMBER || other_check(); }
        boolean other_check() { return true; }
        boolean mixed(Unit other) { return other == COUNT; }
      }
    }`);
    assert.equal(invalid.status, 1, invalid.stderr);
    assert.equal((invalid.stderr.match(/对象与枚举相等必须使用/g) ?? []).length, 8, invalid.stderr);

    const valid = run(`import java.util.Objects;
      class Fixture {
        static final int MAX = 100;

        boolean run(long durationMin, int size, char flag, Long parentId, long current) {
          // 原始类型、数值常量与拆箱后的数值比较保留 ==
          boolean a = durationMin == Long.MAX_VALUE;
          boolean b = size == 0;
          boolean c = flag == 'x';
          boolean d = parentId == current;
          boolean e = Objects.equals(parentId, current);
          String sql = "level == AggregationLevel.DAY";
          // level != AggregationLevel.HOUR
          return a && b && c && d && e && size != Fixture.MAX;
        }
        enum Unit { COUNT, NUMBER;
          static final int LIMIT = 5;

          boolean numeric(int count) { return count == LIMIT; }
          boolean same(Unit other) { return Objects.equals(this, other); }
        }
      }`);
    assert.equal(valid.status, 0, valid.stderr);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('枚举检查依据跨文件声明与 import，而非常量命名', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'neocat-enum-imports-'));
  const helper = fileURLToPath(new URL('./CheckJavaStandards.java', import.meta.url));
  try {
    const enumFile = path.join(dir, 'Kinds.java');
    const fixture = path.join(dir, 'Fixture.java');
    fs.writeFileSync(enumFile, 'package sample; class Kinds { enum Level { low, HIGH } }');
    fs.writeFileSync(fixture, `import sample.Kinds;
      import sample.Kinds.Level;
      class Fixture {
        boolean run(Level level) {
          return (level) != ((Kinds.Level.low)) || Level.HIGH == level;
        }
      }`);
    const result = spawnSync('java', [helper, enumFile, fixture], { encoding: 'utf8' });
    assert.equal(result.status, 1, result.stderr);
    assert.equal((result.stderr.match(/对象与枚举相等必须使用/g) ?? []).length, 2, result.stderr);
    fs.writeFileSync(fixture, `class Fixture {
      static final long MAX = 100L;

      boolean run(long value) { return value == Fixture.MAX; }
    }`);
    const numeric = spawnSync('java', [helper, enumFile, fixture], { encoding: 'utf8' });
    assert.equal(numeric.status, 0, numeric.stderr);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('Objects.equals 枚举替换保持 null、短路、三元与求值顺序', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'neocat-enum-semantics-'));
  const file = path.join(dir, 'Fixture.java');
  try {
    fs.writeFileSync(file, `import java.util.Objects;
      class Fixture {
        enum Level { DAY, HOUR }
        static String calls = "";

        static Level next(String label, Level level) { calls += label; return level; }
        static void check(boolean valid) { if (!valid) throw new AssertionError(calls); }
        public static void main(String[] args) {
          Level absent = null;
          check(!Objects.equals(absent, Level.DAY));
          check(!Objects.equals(Level.DAY, absent));
          check(Objects.equals(absent, absent));
          check(!Objects.equals(Level.DAY, Level.HOUR));
          check(Objects.equals(next("A", Level.DAY), Level.DAY)
              || Objects.equals(next("bad", Level.HOUR), Level.HOUR));
          check(calls.equals("A"));
          check(!Objects.equals(next("B", Level.HOUR), Level.HOUR)
              && Objects.equals(next("bad", Level.DAY), Level.DAY) || true);
          check(calls.equals("AB"));
          check(Objects.equals(next("C", Level.DAY), next("D", Level.DAY)));
          check(calls.equals("ABCD"));
          String result = Objects.equals(next("E", Level.HOUR), Level.DAY) ? "day" : "hour";
          check(result.equals("hour") && calls.equals("ABCDE"));
          // 原始 double 的 EQ/NEQ 不替换为 Double.equals，避免改变 NaN 与正负零语义。
          double positive = 0.0;
          double negative = -0.0;
          check(positive == negative);
          check(!Objects.equals(positive, negative));
        }
      }`);
    const result = spawnSync('java', [file], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
