export const PROBLEM_TYPES = [
  "common/internal-error",
  "common/not-found",
  "common/validation",
  "common/rate-limited",
] as const;

export type ProblemTypeSlug = (typeof PROBLEM_TYPES)[number];
