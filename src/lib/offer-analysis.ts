import type {
  DecisionAnalysis,
  DecisionRequest,
  DimensionInsight,
  EvidenceItem,
  OfferInput,
  OfferSnapshot,
  Priority,
} from "@/lib/types";

const stageScore = {
  startup: { upside: 9, stability: 3, maturity: 4 },
  growth: { upside: 7, stability: 6, maturity: 6 },
  "big-tech": { upside: 5, stability: 9, maturity: 9 },
};

const managerScore = {
  low: 3,
  medium: 6,
  high: 9,
};

const executionScore = {
  chaotic: 3,
  balanced: 7,
  structured: 9,
};

const workModeScore = {
  onsite: 7,
  hybrid: 8,
  remote: 6,
};

const priorityWeights: Record<Priority, Record<string, number>> = {
  "technical depth": { technicalDepth: 1.3, growth: 1.1 },
  "growth speed": { growth: 1.3, personalFit: 1.1 },
  brand: { teamMaturity: 1.05, companyRisk: 1.05 },
  compensation: { personalFit: 1.05 },
  stability: { companyRisk: 1.35, teamMaturity: 1.05 },
  mentorship: { teamMaturity: 1.25, growth: 1.05 },
};

function clamp(score: number) {
  return Math.max(1, Math.min(10, Math.round(score)));
}

function stackDepthScore(stack: string) {
  const lower = stack.toLowerCase();
  let score = 4;

  if (/(agent|llm|eval|retrieval|workflow|platform)/.test(lower)) score += 3;
  if (/(kafka|redis|observability|distributed|governance|performance)/.test(lower))
    score += 2;
  if (/(typescript|python|java|postgres|mysql)/.test(lower)) score += 1;

  return clamp(score);
}

function domainMomentumScore(domain: string) {
  const lower = domain.toLowerCase();
  let score = 5;

  if (/(agent|ai|inference|model|automation)/.test(lower)) score += 3;
  if (/(platform|infra|developer|workflow)/.test(lower)) score += 1;
  if (/(mature|legacy)/.test(lower)) score -= 1;

  return clamp(score);
}

function compensationScore(compensation: string) {
  const matched = compensation.match(/(\d+)/);
  if (!matched) return 6;
  const monthly = Number(matched[1]);
  if (monthly >= 30) return 9;
  if (monthly >= 26) return 8;
  if (monthly >= 22) return 7;
  return 6;
}

function riskPreferenceAdjustment(
  riskAppetite: DecisionRequest["userProfile"]["riskAppetite"],
  offer: OfferInput,
) {
  if (riskAppetite === "high" && offer.stage === "startup") return 1.5;
  if (riskAppetite === "low" && offer.stage === "startup") return -2;
  if (riskAppetite === "low" && offer.stage === "big-tech") return 1.5;
  return 0;
}

function matchTargetScore(target: string, offer: OfferInput) {
  const lowerTarget = target.toLowerCase();
  let score = 5;

  if (
    /(backend|platform|infra)/.test(lowerTarget) &&
    /(platform|api|backend|infra)/.test(offer.role.toLowerCase())
  ) {
    score += 2;
  }

  if (
    /(ai|agent)/.test(lowerTarget) &&
    /(ai|agent|llm|retrieval|eval)/.test(
      `${offer.domain} ${offer.stack}`.toLowerCase(),
    )
  ) {
    score += 2;
  }

  if (/(mentor|guided|learn)/.test(lowerTarget) && offer.managerSupport === "high") {
    score += 1;
  }

  return clamp(score);
}

function dimensionBaseScores(request: DecisionRequest, offer: OfferInput) {
  const technicalDepth =
    stackDepthScore(offer.stack) * 0.55 +
    domainMomentumScore(offer.domain) * 0.25 +
    stageScore[offer.stage].upside * 0.2;

  const growth =
    stageScore[offer.stage].upside * 0.4 +
    managerScore[offer.managerSupport] * 0.35 +
    matchTargetScore(request.userProfile.target, offer) * 0.25;

  const teamMaturity =
    stageScore[offer.stage].maturity * 0.45 +
    executionScore[offer.executionStyle] * 0.35 +
    managerScore[offer.managerSupport] * 0.2;

  const companyRisk =
    stageScore[offer.stage].stability * 0.6 +
    executionScore[offer.executionStyle] * 0.15 +
    workModeScore[offer.workMode] * 0.05 +
    compensationScore(offer.compensation) * 0.2 +
    riskPreferenceAdjustment(request.userProfile.riskAppetite, offer);

  const personalFit =
    compensationScore(offer.compensation) * 0.15 +
    matchTargetScore(request.userProfile.target, offer) * 0.45 +
    managerScore[offer.managerSupport] * 0.2 +
    workModeScore[offer.workMode] * 0.2;

  return {
    technicalDepth: clamp(technicalDepth),
    growth: clamp(growth),
    teamMaturity: clamp(teamMaturity),
    companyRisk: clamp(companyRisk),
    personalFit: clamp(personalFit),
  };
}

function applyPriorityWeights(
  request: DecisionRequest,
  scores: Record<string, number>,
) {
  const weighted = { ...scores };

  for (const priority of request.userProfile.priorities) {
    const weights = priorityWeights[priority];

    for (const [key, multiplier] of Object.entries(weights)) {
      weighted[key] = clamp(weighted[key] * multiplier);
    }
  }

  return weighted;
}

function buildEvidence(offer: OfferInput): EvidenceItem[] {
  return [
    {
      label: `${offer.company} 的岗位信号：${offer.jdSignals}`,
      sourceType: "user input",
      freshness: "needs live verification",
    },
    {
      label: `${offer.company} 的技术栈画像：${offer.stack}`,
      sourceType: "user input",
      freshness: "stable",
    },
    {
      label: `${offer.company} 的协作环境由 ${offer.stage} 阶段和 ${offer.executionStyle} 执行风格推断得出`,
      sourceType: "heuristic",
      freshness: "stable",
    },
  ];
}

function compareDimension(
  key: keyof ReturnType<typeof dimensionBaseScores>,
  title: string,
  weight: number,
  request: DecisionRequest,
  offers: OfferInput[],
): DimensionInsight {
  const [offerA, offerB] = offers;
  const scoresA = applyPriorityWeights(
    request,
    dimensionBaseScores(request, offerA),
  );
  const scoresB = applyPriorityWeights(
    request,
    dimensionBaseScores(request, offerB),
  );
  const scoreA = scoresA[key];
  const scoreB = scoresB[key];
  const winner = scoreA >= scoreB ? offerA.company : offerB.company;

  const verdictMap: Record<string, string> = {
    technicalDepth:
      scoreA === scoreB
        ? "两个机会都能接触到有价值的后端工作，但当前阶段的技术天花板差距还不明显。"
        : `${winner} 当前展现出更强的技术成长斜率，因为它的技术栈和问题域更贴近你未来三年的目标。`,
    growth:
      scoreA === scoreB
        ? "两边的学习速度看起来比较接近，最终差异会更多取决于你对不确定性的接受程度。"
        : `${winner} 更可能更快拉伸你的成长曲线，因为职责范围、带教支持和目标匹配正在同向叠加。`,
    teamMaturity:
      scoreA === scoreB
        ? "两边的流程成熟度相近，因此你需要通过后续面试继续核验真实的经理质量和代码评审体验。"
        : `${winner} 对校招生更友好一些，因为它的协作环境看起来更容易在其中建立节奏，而不是一开始就迷失。`,
    companyRisk:
      scoreA === scoreB
        ? "仅从纸面信息看，两边都没有明显更稳，因此需要用融资、招聘和组织变化等新近信号来打破平局。"
        : `${winner} 是更偏稳健的选择，尤其当你更在意可预期的资金安全边际、入职节奏和更少的结构性意外时。`,
    personalFit:
      scoreA === scoreB
        ? "两个机会都能较好映射到你的目标，因此最后的选择更应该取决于追问后的真实答案。"
        : `${winner} 更贴近你想成为的工程师画像，而不只是短期薪酬包更好看。`,
  };

  const risks =
    winner === offerA.company
      ? [
          `${offerB.company} 如果真实团队质量明显高于 JD 呈现，仍然可能反超。`,
          `${winner} 仍然需要结合最近招聘节奏、团队健康度和真实入职支持再做核验。`,
        ]
      : [
          `${offerA.company} 如果团队真的能让新人从第一天开始承担实质工作，它的上限可能高于当前结构化评分。`,
          `${winner} 如果第一年的职责范围比预期更窄，可能会变成一个过于舒适的选择。`,
        ];

  const followUps =
    key === "companyRisk"
      ? [
          `追问 ${offerA.company} 的资金安全边际、当前招聘节奏，以及过去 6 个月发生了哪些变化。`,
          `追问 ${offerB.company} 团队现在是在继续扩张，还是主要在优化一条成熟业务线。`,
        ]
      : [
          `分别向两边的经理要一个具体例子：优秀校招生前 90 天真正交付过什么。`,
          `继续追问代码评审、导师带教，以及技术决策在每周协作中到底如何发生。`,
        ];

  return {
    key,
    title,
    weight,
    winner,
    scoreA,
    scoreB,
    verdict: verdictMap[key],
    evidence: [...buildEvidence(offerA), ...buildEvidence(offerB)].slice(0, 4),
    risks,
    followUps,
  };
}

function buildSnapshot(
  request: DecisionRequest,
  offer: OfferInput,
  dimensions: DimensionInsight[],
): OfferSnapshot {
  const scores = applyPriorityWeights(request, dimensionBaseScores(request, offer));
  const overallScore = clamp(
    dimensions.reduce((sum, dimension) => {
      const score =
        offer.company === request.offers[0].company
          ? dimension.scoreA
          : dimension.scoreB;
      return sum + score * dimension.weight;
    }, 0) /
      dimensions.reduce((sum, dimension) => sum + dimension.weight, 0),
  );

  const strengths = [
    scores.technicalDepth >= 8
      ? "相对你的目标路径，这个机会能提供更强的技术杠杆。"
      : null,
    scores.growth >= 8 ? "更可能加速你第一年的学习曲线。" : null,
    scores.teamMaturity >= 8
      ? "流程更清晰，经理和协作机制对校招生更友好。"
      : null,
    scores.personalFit >= 8
      ? "与你表达出的职业方向高度一致。"
      : null,
  ].filter(Boolean) as string[];

  const watchouts = [
    scores.companyRisk <= 5
      ? "资金安全边际或组织波动仍然需要实时核验。"
      : null,
    scores.teamMaturity <= 6
      ? "流程信号偏弱，可能会放大入职初期的随机性。"
      : null,
    scores.technicalDepth <= 6
      ? "这套技术栈未必能足够快地朝你的 AI 平台目标持续积累。"
      : null,
  ].filter(Boolean) as string[];

  return {
    id: offer.id,
    company: offer.company,
    role: offer.role,
    overallScore,
    strengths:
      strengths.length > 0
        ? strengths
        : ["这个机会整体比较均衡，但上限优势暂时还不够尖锐。"],
    watchouts:
      watchouts.length > 0
        ? watchouts
        : ["当前离线输入里没有明显红旗，但新近公开信号仍然很重要。"],
  };
}

export function analyzeOffersHeuristic(
  request: DecisionRequest,
): DecisionAnalysis {
  const dimensions = [
    compareDimension("growth", "成长速度", 1.2, request, request.offers),
    compareDimension(
      "technicalDepth",
      "技术深度",
      1.25,
      request,
      request.offers,
    ),
    compareDimension(
      "teamMaturity",
      "团队成熟度",
      1.05,
      request,
      request.offers,
    ),
    compareDimension(
      "companyRisk",
      "公司稳定性",
      1.1,
      request,
      request.offers,
    ),
    compareDimension(
      "personalFit",
      "个人匹配度",
      1.35,
      request,
      request.offers,
    ),
  ];

  const snapshots = request.offers
    .map((offer) => buildSnapshot(request, offer, dimensions))
    .sort((left, right) => right.overallScore - left.overallScore);

  const [first, second] = snapshots;

  return {
    generatedAt: new Date().toISOString(),
    engine: {
      mode: "heuristic",
      requestedMode: "heuristic",
      label: "离线规则分析",
      detail:
        "当前基线只使用结构化规则判断，尚未抓取新近公开信号。",
    },
    recommendation: {
      winner: first.company,
      summary: `${first.company} 目前仍是更强的第一选择，但这点领先只有在实时核验支持当前假设时才真正成立。`,
      rationale: `${first.company} 之所以领先，是因为它能同时叠加更多你的核心优先项：${first.strengths[0].toLowerCase()} ${first.strengths[1]?.toLowerCase() ?? ""}`.trim(),
      caution:
        "这次结果仍然属于离线研究分析。在你真正做决定前，团队质量、经理质量，以及最近业务变化都应该用更新的证据继续核验。",
    },
    pipeline: [
      {
        title: "问题定义",
        detail:
          "把模糊的职业选择题拆成五个可比较的决策维度，而不是只给一个笼统分数。",
        status: "done",
      },
      {
        title: "证据聚合",
        detail:
          "把每个 Offer 的 JD、技术栈、阶段、执行风格和经理信号映射成可比较的证据桶。",
        status: "done",
      },
      {
        title: "风险校正",
        detail:
          "结合你的风险偏好和职业目标，对稳定性与上限之间的权衡做了校正。",
        status: "done",
      },
      {
        title: "实时核验清单",
        detail:
          "把公司动向、招聘节奏和经理质量标成对时效敏感的信息，不能只依赖缓存知识。",
        status: "watch",
      },
    ],
    snapshots: [first, second],
    dimensions,
    freshness: {
      stableKnowledge: [
        "岗位和技术栈与长期技术深度目标的匹配程度",
        "不同公司阶段在上限与结构化之间的典型权衡",
        "经理支持度如何改变校招生的成长速度",
      ],
      needsVerification: [
        "当前团队的招聘强度和组织变动",
        "公司的资金安全边际、产品势能和最近公开信号变化",
        "发 Offer 后真实工作内容是否与 JD 一致",
      ],
    },
    liveResearch: null,
  };
}
