export const PROBLEM_TYPES = [
  "common/internal-error",
  "common/not-found",
  "common/validation",
  "common/rate-limited",
  "auth/invalid-token",
  "auth/non-dlsu-account",
  "auth/unauthenticated",
  "auth/forbidden",
] as const;

export type ProblemTypeSlug = (typeof PROBLEM_TYPES)[number];
