# Offer 决策工作台

一个面向真实求职决策场景的智能工作台，用于接收候选人的 `Offer`、`JD`、面试反馈和 `HR` 补充信息，生成带证据、带风险提示、带核验建议的对比分析结果。

系统采用 `Retrieval-first` 研究架构：先检索公开信号，再由模型完成结构化总结，最后由 `Java` 决策服务完成评分合并、降级治理和结果编排。

## 核心能力

- 双 Offer 对比：围绕成长性、技术深度、团队成熟度、稳定性与个人匹配度进行综合分析
- 原文接入：支持直接粘贴 `JD`、`Offer`、面试纪要、`HR` 补充信息
- 结构化解析：将非结构化文本整理为可编辑字段，降低录入成本
- 工作台持久化：保存候选人画像、Offer 信息、研究运行记录与分析结果
- 异步研究任务：先返回基线分析，再在后台补全实时研究结果
- 引用与核验：输出公开信号来源、风险点和待确认事项
- 多级降级：实时研究失败时自动切换到更稳定路径，避免整条链路不可用

## 产品界面

- 候选人画像：职业方向、风险偏好、优先项
- 机会录入区：两份机会并排编辑与比较
- 原文接入区：解析 `JD / Offer / 面试反馈 / HR 信息`
- 研究结果区：推荐结论、横向对比、风险提示、引用来源
- 工作台历史：查看最近研究运行并恢复历史结果

## 系统架构

### 前端

- `Next.js 16`
- `React 19`
- `TypeScript`
- `Tailwind CSS 4`

### 后端

- `Spring Boot 3`
- `Spring Web`
- `Spring Data JPA`
- `H2`（本地持久化）

### 研究引擎

- `Retrieval-first` 研究链路
- Kimi / Moonshot Live Provider
- OpenAI-compatible 结构化模型
- Evidence Quality Gate
- Retrieval Cache / Freshness Gate
- 宽松 JSON 解析与超时重试
- Deterministic Provider / Mock Search Gateway

## 决策流程

1. 用户录入两份 Offer 或粘贴原始材料
2. 后端解析文本并生成结构化草稿
3. 基于稳定字段生成一版基线分析
4. 异步触发实时研究任务
5. 检索层收集公开证据并进行质量门控
6. 模型基于证据生成结构化研究卡片
7. Java 服务合并研究结果、评分、风险和建议
8. 前端展示最终推荐、对比视图与引用来源

## 运行模式

- `标准分析`
  - 启用实时研究链路，适合完整体验
- `快速判断`
  - 仅使用本地规则分析，响应最快
- `离线体验`
  - 使用本地固定证据与 deterministic provider，适合稳定演示和开发联调

## 快速开始

### 1. 启动前端

```bash
npm install
npm run dev
```

前端默认运行在：

```text
http://localhost:3000
```

### 2. 启动后端

```bash
cd server
mvn spring-boot:run
```

后端默认运行在：

```text
http://localhost:8080
```

### 3. 打开工作台

访问：

```text
http://localhost:3000
```

## 环境变量

### 前端

```text
NEXT_PUBLIC_DECISION_API_URL=http://localhost:8080/api/v1/decision/analyze
```

### 实时研究总开关

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

### 超时预算

```bash
export LIVE_RESEARCH_CONNECT_TIMEOUT_SECONDS=10
export LIVE_RESEARCH_REQUEST_TIMEOUT_SECONDS=90
export LIVE_RESEARCH_TIMEOUT_SECONDS=90
```

## 本地推荐配置

### 稳定离线联调

```bash
export APP_LIVE_RESEARCH_ENABLED=true
export APP_RETRIEVAL_ENABLED=true
export APP_RETRIEVAL_PROVIDER=generic-json
export APP_RETRIEVAL_ENDPOINT=http://127.0.0.1:8080/api/v1/mock-search/search
```

前端选择 `离线体验` 即可运行完整链路。

### 实时研究联调

```bash
export APP_LIVE_RESEARCH_ENABLED=true
export APP_RETRIEVAL_ENABLED=true
export LIVE_STRUCTURING_PROVIDER=openai-compatible
```

前端选择 `标准分析`，系统会先返回基线结果，再异步补全实时研究内容。

## 关键接口

### 决策分析

- `POST /api/v1/decision/analyze`
- `GET /api/v1/decision/capabilities`

### 工作台

- `GET /api/v1/workspace/default`
- `PUT /api/v1/workspace/default/profile`
- `PUT /api/v1/workspace/default/offers/{slot}`
- `POST /api/v1/workspace/default/intake/parse`

### 异步研究任务

- `POST /api/v1/workspace/default/run-jobs`
- `GET /api/v1/workspace/default/run-jobs/{runId}`

## 项目结构

```text
.
├── src/                    # Next.js 前端
├── public/                 # 静态资源
├── server/                 # Spring Boot 后端
│   ├── src/main/java/...   # 决策、研究、工作台、检索实现
│   └── src/test/java/...   # 后端测试
├── .env.example
└── README.md
```

## 质量保障

- 前端静态检查：`npm run lint`
- 前端生产构建：`npm run build`
- 后端单元测试：`cd server && mvn test`

## 部署说明

- 前端可部署到 `Vercel` 或任意支持 `Next.js` 的 Node 环境
- 后端可部署为标准 `Spring Boot` 服务
- 当前默认使用 `H2` 进行本地持久化，生产环境建议切换到 `PostgreSQL`
- 检索层支持接入真实搜索源或自建搜索网关

## License

本仓库采用根目录中的 [LICENSE](LICENSE)。
