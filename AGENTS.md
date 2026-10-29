# AI Agent 指南 (Agent Guidelines)

本文档面向在此工程中进行开发、重构与测试的 AI 助手。

## 测试规范与策略

所有改动在提交前必须执行完整测试验证。关于架构、Fake 数据仓库及各层测试执行指南，详见：
- [测试策略与执行指南](docs/testing.md)

### 关键准则：
1. **测试隔离**：单元测试严禁发起真实 HTTP 请求，所有与数据源相关的测试必须使用 `FakeTransitRepository`。
2. **ViewModel 协程测试**：带轮询循环的 ViewModel 必须通过 `enableAutoPolling = false` 规避 `advanceUntilIdle()` 调度器死循环。
3. **验证命令**：
   - 运行全量测试：`./gradlew test`
   - 生成覆盖率报告：`./gradlew jacocoTestReport`
