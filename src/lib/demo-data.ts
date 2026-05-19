import type { DecisionRequest } from "@/lib/types";

export const demoRequest: DecisionRequest = {
  userProfile: {
    target: "我希望在 3 年内成长为兼具产品理解和 AI Infra 视角的后端工程师。",
    riskAppetite: "medium",
    priorities: ["technical depth", "growth speed", "mentorship", "stability"],
  },
  offers: [
    {
      id: "offer-a",
      company: "Nova Agents",
      role: "AI 平台工程师",
      city: "上海",
      compensation: "28k x 16",
      stage: "startup",
      domain: "面向企业团队的 Agent 工作流平台",
      workMode: "hybrid",
      stack: "TypeScript、Python、Postgres、Redis、LangGraph、可观测性、工作流引擎",
      managerSupport: "high",
      executionStyle: "balanced",
      jdSignals:
        "负责 Agent 编排、评测工具链、检索流水线和开发者平台 API。",
      notes:
        "创始工程团队技术氛围浓，层级更少、职责更宽，但路线图变化可能更快。",
    },
    {
      id: "offer-b",
      company: "CloudSuite",
      role: "后端工程师",
      city: "杭州",
      compensation: "24k x 16",
      stage: "big-tech",
      domain: "成熟的 SaaS 协作产品",
      workMode: "onsite",
      stack: "Java、Spring、MySQL、Kafka、内部平台工具链",
      managerSupport: "medium",
      executionStyle: "structured",
      jdSignals:
        "聚焦核心业务 API、服务治理、性能优化和稳定性建设。",
      notes:
        "平台更大、流程更成熟、内部流动机会更多，但前期职责边界可能更窄。",
    },
  ],
};
