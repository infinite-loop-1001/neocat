// 规格共享动态配置和 TimeProvider 全局时钟；配置扩展恢复配置，时钟由单测主动重置，不允许并行修改。
runner {
    parallel {
        enabled false
    }
}
