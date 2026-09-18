import { test, expect } from "@playwright/test";

test("landing route renders", async ({ page }) => {
  await page.route("**/api/**", (route) =>
    route.fulfill({ json: { status: "UP" } }),
  ); 

  await page.goto("/");

  await expect(page.getByRole("heading")).toBeVisible();
});
