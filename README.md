# Offer 决策工作台

一个面向校招 / 社招求职者的 `Offer Decision Copilot`。  
它不是泛化聊天框，而是一个围绕真实决策场景设计的研究工作台：用户可以导入自己的 `Offer`、`JD`、面试反馈和 `HR` 补充信息，系统会先做结构化整理，再产出一份带依据、带保留项、带核验清单的比较建议。

## 为什么做这个项目

多数 AI 项目停留在“问一句，回一段话”。  
但 Offer 决策本质上不是一次性问答，而是一个高不确定性的决策流程：

- 先明确职业目标和风险偏好
- 再把多个机会拆成可比较的维度
- 区分稳定知识与必须实时核验的信息
- 用公开信号补充结论，而不是完全依赖模型直觉
- 最后输出建议、风险点和下一轮要问清楚的问题

这个项目想解决的，就是“如何把 LLM、检索和传统后端工程真正组合成一个可用产品”，而不是做一个 AI 套壳 demo。

## 当前能力

### 产品能力

- 候选人画像录入：目标岗位、风险偏好、优先项
- 双 Offer 对比：支持并排录入两个机会
- 工作台持久化：Offer、画像、运行记录保存在后端
- 原文接入：支持直接粘贴 `JD / Offer 原文 / 面试反馈 / HR 补充信息`
- 结构化解析：把原始文本整理成可编辑草稿
- 决策记忆：保留最近分析记录，方便回放和比较

### 研究引擎能力

- `Heuristic` 基线分析：完全本地规则判断
- `Retrieval-first` 实时研究：先拿公开证据，再交给模型做结构化总结
- Evidence Quality Gate：去重、URL 规范化、质量分、时效性惩罚
- Fallback 机制：研究失败时自动回退到更安全的路径
- Stage Timings：可追踪每个研究阶段的耗时和状态
- 宽松 JSON 解析：兼容真实 provider 返回代码块 / 夹杂说明文字的情况

### 演示能力

- `标准分析`：真实研究链路
- `快速判断`：只跑规则基线
- `离线体验`：使用本地固定证据跑完整流程，不依赖外部模型

## 技术栈

### 前端

- `Next.js 16`
- `React 19`
- `TypeScript`
- `Tailwind CSS 4`

### 后端

- `Spring Boot 3`
- `Spring Web`
- `Spring Data JPA`
- `H2`（当前用于本地持久化）

### AI / Research

- Retrieval-first 架构
- Kimi / Moonshot live provider（保留）
- OpenAI-compatible structuring provider
- 本地 mock search gateway
- Deterministic live provider（本地可复现演示链路）

## 系统设计

### 1. 输入层

用户可以通过两种方式进入系统：

- 直接手填两份 Offer
- 粘贴 `JD / Offer / 面试纪要 / HR 信息` 原文，让后端先解析成结构化草稿

### 2. 决策层

后端会先基于稳定信息生成一版基线判断：

- 成长速度
- 技术深度
- 团队成熟度
- 公司稳定性
- 个人匹配度

### 3. 研究层

当前主链路不是“让一个模型自己搜、自己想、自己输出”，而是：

1. 检索层先收集公开证据
2. 证据经过质量门控、缓存和时效性判断
3. 结构化模型只基于这些证据生成研究结论
4. Java 后端再把研究结果合并回评分与建议

### 4. 降级层

如果实时研究失败：

- 优先回退到 retrieval fallback
- 再不行回退到本地 deterministic / heuristic

这样系统不会因为单个模型超时就完全不可用。

## 项目结构

```text
.
├── src/                  # Next.js 前端
├── public/               # 静态资源
├── server/               # Spring Boot 后端
│   ├── src/main/java/... # 决策、研究、工作台、检索相关实现
│   └── src/test/java/... # 后端测试
├── .env.example
└── README.md
```

## 本地启动

### 1. 启动前端

```bash
npm install
npm run dev
```

默认地址：

```text
http://localhost:3000
```

### 2. 启动后端

```bash
cd server
mvn spring-boot:run
```

默认地址：

```text
http://localhost:8080
```

## 环境变量

前端默认读取：

```text
NEXT_PUBLIC_DECISION_API_URL=http://localhost:8080/api/v1/decision/analyze
```

### 开启实时研究

```bash
export APP_LIVE_RESEARCH_ENABLED=true
```

### Kimi / Moonshot

```bash
export APP_LIVE_RESEARCH_PROVIDER=kimi
export MOONSHOT_API_KEY=your_key_here
export MOONSHOT_BASE_URL=https://api.moonshot.cn/v1
export MOONSHOT_MODEL=kimi-k2.6
```

### OpenAI-compatible 结构化模型

```bash
export LIVE_STRUCTURING_PROVIDER=openai-compatible
export LIVE_STRUCTURING_API_KEY=your_structuring_key
export LIVE_STRUCTURING_BASE_URL=https://your-openai-compatible-endpoint/v1
export LIVE_STRUCTURING_MODEL=your-model-id
```

### 检索层

```bash
export APP_RETRIEVAL_ENABLED=true
export APP_RETRIEVAL_PROVIDER=generic-json
export APP_RETRIEVAL_ENDPOINT=http://127.0.0.1:8080/api/v1/mock-search/search
```

### 实时研究超时

当前可以通过下面的变量调整结构化请求预算：

```bash
export LIVE_RESEARCH_CONNECT_TIMEOUT_SECONDS=10
export LIVE_RESEARCH_REQUEST_TIMEOUT_SECONDS=90
export LIVE_RESEARCH_TIMEOUT_SECONDS=90
```

## 当前推荐的体验方式

### 想看完整产品流程

- 启动前后端
- 使用 `标准分析`
- 粘贴自己的真实 `JD / Offer` 原文

### 想稳定本地试用

- 使用 `离线体验`
- 不依赖外部模型，也能走完整研究流程

### 想做快速基线比较

- 使用 `快速判断`

## 现在已经做到什么程度

### 作为面试项目

已经远超普通 CRUD：

- 有明确产品形态
- 有 Java 后端主导的业务系统
- 有 Retrieval-first 研究架构
- 有异步任务、降级策略、可观测 trace
- 有真实模型接入与真实超时治理

### 作为个人体验产品

已经可以用了，尤其适合：

- 先快速比较两份机会
- 整理面试信息和 HR 补充信息
- 形成下一轮沟通的问题清单
- 留下自己的决策过程记录

但它还不是“完全成品”，目前仍有边界：

- 复杂薪资包、职级、部门差异的理解还不够细
- 多用户体系还没做
- 当前数据库还是 `H2`，还没切正式 `PostgreSQL`
- 真实搜索源能力还可以继续增强
- provider failover 还可以做得更强

## 下一步规划

1. 接入更强的真实搜索源
2. 做多模型 failover，提升实时研究稳定性
3. 引入 `PostgreSQL` 和更正式的用户持久化
4. 补充更细的 Offer 字段：职级、业务线、薪资包细项、汇报关系
5. 加强“为什么这次建议和上次不一样”的版本对比能力

## 适合怎么讲这个项目

如果你用它做面试项目，建议重点讲这三件事：

1. 这不是聊天框，而是一个决策工作台  
2. 研究引擎采用 retrieval-first，而不是把搜网和推理都压给单模型  
3. 真正的难点不只是接模型，而是超时、降级、证据质量和可追溯性

---

如果你希望把它继续往产品级推进，最值得做的下一步不是继续堆 UI，而是把：

- `真实搜索源`
- `provider failover`
- `正式数据库`
- `复杂 Offer 字段建模`

这几块补齐。届时它会更像一个真正可持续演进的 AI 决策产品，而不是单轮分析工具。
