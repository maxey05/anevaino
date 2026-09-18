import { describe, it, expect, vi, afterEach } from "vitest";
import { z } from "zod";
import problemTypesJson from "../../../shared/problem-types.json";
import { PROBLEM_TYPES } from "./problemTypes";
import { apiFetch, ApiProblem, ApiSchemaError } from "./client";

afterEach(() => vi.unstubAllGlobals());

describe("problem types", () => {
  it("match shared/problem-types.json", () => {
    const expected = problemTypesJson.map((p) => p.slug).sort();
    expect([...PROBLEM_TYPES].sort()).toEqual(expected);
  });
});

describe("apiFetch", () => {
  it("sends same-origin credentials", async () => {
    const fetchMock = vi.fn(
      async () =>
        new Response(JSON.stringify({ status: "UP" }), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await apiFetch("/api/health", z.object({ status: z.string() }));

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/health",
      expect.objectContaining({ credentials: "same-origin" }),
    );
  });

  it("turns problem+json into ApiProblem", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(
            JSON.stringify({
              type: "common/not-found",
              title: "Not found",
              status: 404,
              detail: "Curriculum 42 was not found.",
              requestId: "abc-123",
            }),
            {
              status: 404,
              headers: { "content-type": "application/problem+json" },
            },
          ),
      ),
    );

    await expect(
      apiFetch("/api/curricula/42", z.object({})),
    ).rejects.toBeInstanceOf(ApiProblem);
  });

  it("reject success body that fails its schema", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(JSON.stringify({ wrong: "shape" }), {
            status: 200,
            headers: { "content-type": "application/json" },
          }),
      ),
    );

    await expect(
      apiFetch("/api/health", z.object({ status: z.string() })),
    ).rejects.toBeInstanceOf(ApiSchemaError);
  });
});
