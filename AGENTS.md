# Agent 指南

工程开发、重构与测试指南。

## 测试策略

所有代码变更提交前需通过测试验证。详细说明见 [测试策略与指南](docs/testing.md)。

### 准则

1. **测试隔离**。单元测试不发起真实 HTTP 请求，所有数据层测试使用 `FakeTransitRepository`。
2. **协程测试**。带轮询的 ViewModel 传入 `enableAutoPolling = false`，避免测试调度器死循环。
3. **验证命令**
   - 运行全量测试：`./gradlew test`
   - 生成覆盖率报告：`./gradlew jacocoTestReport`
