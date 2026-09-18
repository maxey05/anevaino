import { z } from "zod";

const problemSchema = z.looseObject({
  type: z.string(),
  title: z.string(),
  status: z.number(),
  detail: z.string(),
  requestId: z.string(),
});

export class ApiProblem extends Error {
  constructor(
    readonly type: string,
    readonly status: number,
    readonly title: string,
    readonly detail: string,
    readonly requestId: string,
    readonly extensions: Record<string, unknown>,
  ) {
    super(detail);
  }
}

export class ApiSchemaError extends Error {}

export async function apiFetch<T>(
  path: string,
  schema: z.ZodType<T>,
  init: RequestInit = {},
): Promise<T> {
  const res = await fetch(path, {
    ...init,
    credentials: "same-origin",
    headers: {
      Accept: "application/json",
      ...init.headers,
    },
  });

  if (!res.ok) {
    const contentType = res.headers.get("content-type") ?? "";
    if (contentType.includes("application/problem+json")) {
      const raw = (await res.json()) as Record<string, unknown>;
      const parsed = problemSchema.parse(raw);
      const { type, title, status, detail, requestId, ...extensions } = raw;
      return Promise.reject(
        new ApiProblem(
          parsed.type,
          parsed.status,
          parsed.title,
          parsed.detail,
          parsed.requestId,
          extensions,
        ),
      );
    }
    return Promise.reject(
      new ApiProblem(
        "common/internal-error",
        res.status,
        "Internal error",
        res.statusText,
        "",
        {},
      ),
    );
  }

  if (res.status === 204) {
    return schema.parse(undefined);
  }

  const body: unknown = await res.json();
  const result = schema.safeParse(body);
  if (!result.success) {
    throw new ApiSchemaError(
      `Response for ${path} did not match its schema: ${result.error.message}`,
    );
  }
  return result.data;
}
