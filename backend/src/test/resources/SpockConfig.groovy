// 规格共享动态静态字段；测试扩展负责隔离/恢复，不允许并行修改。
runner {
    parallel {
        enabled false
    }
}
