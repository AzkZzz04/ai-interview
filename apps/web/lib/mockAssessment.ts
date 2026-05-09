export type AssessmentScoreKey =
  | "technicalDepth"
  | "impact"
  | "clarity"
  | "relevance"
  | "ats";

export type Assessment = {
  overallScore: number;
  scores: Record<AssessmentScoreKey, number>;
  strengths: string[];
  weaknesses: string[];
  recommendations: Array<{
    section: string;
    priority: "high" | "medium" | "low";
    message: string;
  }>;
  modelProvider?: string;
};

export type InterviewQuestion = {
  id: string;
  category: string;
  difficulty: "Warmup" | "Core" | "Deep Dive";
  questionText: string;
  expectedSignals: string[];
};

export type AnswerFeedback = {
  score: number;
  summary: string;
  nextStep: string;
  strengths?: string[];
  gaps?: string[];
  betterAnswerOutline?: string[];
  followUpQuestion?: string;
  modelProvider?: string;
};

const technicalTerms = [
  "java",
  "spring",
  "postgres",
  "redis",
  "kubernetes",
  "aws",
  "gcp",
  "react",
  "next",
  "typescript",
  "microservices",
  "distributed",
  "latency",
  "observability",
  "security"
];

export function createAssessment(resumeText: string, jobDescription: string): Assessment {
  const text = `${resumeText}\n${jobDescription}`.toLowerCase();
  const resumeOnly = resumeText.toLowerCase();
  const matchedTerms = technicalTerms.filter((term) => text.includes(term));
  const hasMetrics = /\b\d+(\.\d+)?(%|x|ms|s|k|m| users| requests| qps| rps| gb| tb)\b/i.test(
    resumeText
  );
  const hasRoleMatch = jobDescription.trim().length === 0 || overlapScore(resumeText, jobDescription) > 0.08;
  const hasClearSections = ["experience", "skills", "education"].filter((section) =>
    resumeOnly.includes(section)
  ).length;

  const technicalDepth = clamp(55 + matchedTerms.length * 3, 45, 92);
  const impact = hasMetrics ? 82 : 62;
  const clarity = clamp(58 + hasClearSections * 8, 50, 88);
  const relevance = hasRoleMatch ? clamp(68 + matchedTerms.length * 2, 55, 91) : 56;
  const ats = clamp(60 + hasClearSections * 7 + (resumeText.length > 1200 ? 6 : 0), 52, 90);
  const overallScore = Math.round((technicalDepth + impact + clarity + relevance + ats) / 5);

  return {
    overallScore,
    scores: {
      technicalDepth,
      impact,
      clarity,
      relevance,
      ats
    },
    strengths: [
      matchedTerms.length > 3
        ? "Strong technical keyword coverage for backend and platform screening"
        : "Clear base experience for a technical resume review",
      hasClearSections > 1 ? "Resume structure is parseable across core sections" : "Core experience is visible"
    ],
    weaknesses: [
      hasMetrics
        ? "Some technical claims still need more ownership and tradeoff detail"
        : "Impact is under-supported because bullets do not include measurable outcomes",
      hasRoleMatch
        ? "Role alignment can improve with more explicit requirement coverage"
        : "Job description alignment is weak or not yet provided"
    ],
    recommendations: [
      {
        section: "Experience",
        priority: "high",
        message:
          "Rewrite the strongest project bullet with scale, constraint, action, and measurable result."
      },
      {
        section: "Skills",
        priority: "medium",
        message:
          "Group languages, frameworks, databases, infrastructure, and observability tools for faster scanning."
      },
      {
        section: "Projects",
        priority: "medium",
        message:
          "Add one architecture-focused bullet that explains system boundaries, data flow, and production tradeoffs."
      }
    ]
  };
}

export function createQuestions(resumeText: string, jobDescription: string): InterviewQuestion[] {
  const stack = technicalTerms.filter((term) => `${resumeText} ${jobDescription}`.toLowerCase().includes(term));
  const primaryStack = stack.slice(0, 3).join(", ") || "your primary stack";

  return [
    {
      id: "resume-deep-dive",
      category: "Resume Deep Dive",
      difficulty: "Warmup",
      questionText:
        "Choose the most technically complex project on your resume. What problem did it solve, and what was your direct ownership?",
      expectedSignals: ["clear project context", "personal ownership", "technical constraints", "measured result"]
    },
    {
      id: "architecture",
      category: "System Design",
      difficulty: "Deep Dive",
      questionText: `Design how you would evolve a service using ${primaryStack} when traffic grows by 10x.`,
      expectedSignals: ["bottleneck identification", "data model choices", "caching", "observability", "failure modes"]
    },
    {
      id: "debugging",
      category: "Production Debugging",
      difficulty: "Core",
      questionText:
        "A previously stable endpoint becomes slow after a release. Walk through your debugging process from alert to rollback or fix.",
      expectedSignals: ["hypothesis-driven debugging", "logs and traces", "database checks", "risk control"]
    },
    {
      id: "behavioral",
      category: "Collaboration",
      difficulty: "Core",
      questionText:
        "Tell me about a time you disagreed with a technical direction. How did you evaluate tradeoffs and move the team forward?",
      expectedSignals: ["specific conflict", "tradeoff reasoning", "communication", "outcome"]
    }
  ];
}

export function scoreAnswer(answer: string): AnswerFeedback {
  const hasStructure = /(first|second|finally|because|tradeoff|result)/i.test(answer);
  const hasDetail = answer.length > 280;
  const hasMetrics = /\d/.test(answer);
  const score = clamp(48 + (hasStructure ? 18 : 0) + (hasDetail ? 18 : 0) + (hasMetrics ? 10 : 0), 40, 94);

  return {
    score,
    summary:
      score > 78
        ? "Strong answer shape. Add one sharper technical tradeoff to make it interview-ready."
        : "The answer needs more structure, concrete technical detail, and a clearer outcome.",
    nextStep:
      "Use context, action, tradeoff, result, and follow-up learning as the answer spine."
  };
}

function overlapScore(left: string, right: string) {
  const leftWords = toWordSet(left);
  const rightWords = toWordSet(right);
  if (leftWords.size === 0 || rightWords.size === 0) {
    return 0;
  }
  let overlap = 0;
  leftWords.forEach((word) => {
    if (rightWords.has(word)) {
      overlap += 1;
    }
  });
  return overlap / Math.max(leftWords.size, rightWords.size);
}

function toWordSet(value: string) {
  return new Set(
    value
      .toLowerCase()
      .split(/[^a-z0-9+#.]+/)
      .filter((word) => word.length > 2)
  );
}

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, Math.round(value)));
}
