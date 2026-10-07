package com.neocat.common.locking;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 在同一 MySQL 事务内串行执行共享元数据临界区，禁止用于进程内桶刷盘。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MySqlLocked {
    String value();
}
