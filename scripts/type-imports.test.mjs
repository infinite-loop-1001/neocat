import { test } from 'node:test';
import assert from 'node:assert/strict';
import { analyzeImports, codeOnly, packageTypes, shortenImports } from './type-imports.mjs';

test('注解、泛型、构造调用、静态成员、方法引用和嵌套类型缩短并显式导入', () => {
  const source = `package demo;
import java.util.Map;
@org.springframework.stereotype.Component
class Example {
  java.util.List<java.time.Instant> values = new java.util.ArrayList<>();
  java.util.function.Supplier<java.time.Instant> time = java.time.Instant::parse;
  Class<?> type = java.util.Map.Entry.class;
  java.time.Instant now() { return java.time.Instant.EPOCH; }
}`;
  const analysis = analyzeImports(source);
  const result = shortenImports(source, analysis);
  assert.equal(analysis.edits.length, 10);
  for (const type of ['org.springframework.stereotype.Component', 'java.time.Instant', 'java.util.ArrayList', 'java.util.List', 'java.util.function.Supplier']) {
    assert.ok(result.includes(`import ${type};`), result);
  }
  assert.ok(result.includes('@Component'));
  assert.ok(result.includes('Map.Entry.class'));
  assert.ok(result.includes('Instant::parse'));
  assert.equal(analyzeImports(result).edits.length, 0);
});

test('字符串、转义、文本块、Groovy 字符串和注释保留原字节', () => {
  const source = `package demo;
class Example {
  String value = "java.util.List \\\" com.neocat.Foo";
  String block = """java.util.List
    com.neocat.Foo""";
  // @org.springframework.stereotype.Component
  /* java.time.Instant.class */
  def single = 'java.time.Instant'
  def triple = '''com.neocat.Foo'''
  def regex = /com.neocat.Foo/
  def dollar = $/com.neocat.Foo/$
}`;
  assert.equal(analyzeImports(source).edits.length, 0);
  assert.equal(shortenImports(source, analyzeImports(source)), source);
  assert.equal(codeOnly(source).length, source.length);
});

test('只有 java.lang 直属类型免 import，其子包仍必须显式导入', () => {
  const analysis = analyzeImports('class Example { java.lang.String value; java.lang.reflect.Field field; }');
  assert.deepEqual(analysis.imports, ['java.lang.reflect.Field']);
});

test('外部依赖常见包根、无包 Java 文件与 CRLF 都可缩短并保持幂等', () => {
  const source = 'class Example { net.example.Foo a; io.example.Bar b; }\r\n';
  const result = shortenImports(source, analyzeImports(source));
  assert.ok(result.startsWith('import io.example.Bar;\r\nimport net.example.Foo;\r\n\r\n'));
  assert.equal(analyzeImports(result).edits.length, 0);
});

test('同名冲突只保留另一类型，全限定名不能靠同名重复书写制造例外', () => {
  const source = `package demo;
import java.sql.Date;
class Example {
  java.sql.Date sql;
  java.util.Date util;
  java.util.List<String> list;
}`;
  const analysis = analyzeImports(source);
  assert.deepEqual(analysis.exceptions.map(item => item.qualified), ['java.util.Date']);
  assert.deepEqual(analysis.edits.map(item => item.qualified), ['java.sql.Date', 'java.util.List']);
  const result = shortenImports(source, analysis);
  assert.ok(result.includes('Date sql;'));
  assert.ok(result.includes('java.util.Date util;'));
});

test('同包、同文件声明、嵌套类型和类型参数冲突不改变绑定', () => {
  const index = packageTypes(['package demo; class Date {}', 'package demo; class Peer {}']);
  const source = `package demo;
class Example<List> {
  class Instant {}
  java.util.Date other;
  java.time.Instant nested;
  java.util.List<String> parameter;
  demo.Peer samePackage;
}`;
  const analysis = analyzeImports(source, index);
  assert.equal(analysis.exceptions.length, 3);
  const result = shortenImports(source, analysis);
  assert.ok(result.includes('Peer samePackage;'));
  assert.ok(!result.includes('import demo.Peer'));
});

test('无现有 import 时优先导入一个冲突类型，其余保留全限定名', () => {
  const source = 'class Example { java.sql.Date sql; java.util.Date util; }';
  const analysis = analyzeImports(source);
  const result = shortenImports(source, analysis);
  assert.deepEqual(analysis.imports, ['java.sql.Date']);
  assert.ok(result.startsWith('import java.sql.Date;'));
  assert.equal(analysis.exceptions.length, 1);
});

test('嵌套 DTO 的通配导入与同名领域类型冲突，保留原有参数绑定', () => {
  const index = packageTypes(['package com.neocat.api; class Dtos { public static class InitRequest {} }']);
  const source = `package demo;
import com.neocat.api.Dtos.*;
class Convert {
  com.neocat.domain.InitRequest convert(InitRequest request) {
    return new com.neocat.domain.InitRequest();
  }
}`;
  assert.deepEqual([...index.get('com.neocat.api.Dtos')], ['InitRequest']);
  const analysis = analyzeImports(source, index);
  assert.equal(analysis.exceptions.length, 2);
  assert.equal(shortenImports(source, analysis), source);
});

test('Groovy alias 与通配 import 中已使用的同名类型受到保护，不误报对象属性', () => {
  const index = packageTypes(['package domain; class Node {}']);
  const source = `package demo
import java.time.Instant as Moment
import domain.*
class Example {
  Node node
  com.neocat.protocol.Node protocol
  java.time.Instant time
  def run(result, metadata) { result.HITS; result.QPS; metadata.resource.URL }
}`;
  const analysis = analyzeImports(source, index);
  assert.deepEqual(analysis.exceptions.map(item => item.qualified), ['com.neocat.protocol.Node']);
  const result = shortenImports(source, analysis);
  assert.ok(result.includes('Moment time'));
  assert.ok(result.includes('result.HITS; result.QPS; metadata.resource.URL'));
  assert.ok(!result.includes('import java.time.Instant\n'));
});
