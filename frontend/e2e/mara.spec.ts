import { expect, test, type Browser, type Page } from "@playwright/test";
import { createHash } from "node:crypto";
import { apiCall, expectAccessible, mailToken, signedInAccount, totp } from "./support";

/**
 * MARA Stage 2 in the browser, against the full stack (exit criteria): the owner creates a
 * customer, a site and a work order, assigns two technicians and reassigns one by drag and
 * drop; technicians can't use the web app; staff levels and checklists (M-OF-01 to M-OF-05).
 */
test.describe("MARA work orders (full stack)", () => {
  test.skip(() => process.env.E2E_EXPECT_API !== "true", "API not part of this run");
  test.setTimeout(240_000);

  const unique = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  const password = "ein langer Satz als Passwort";

  /** Invites a technician and signs them up through the API in a browser of their own. */
  async function technician(owner: Page, browser: Browser, name: string): Promise<{ email: string; page: Page }> {
    const email = `techniker-${unique()}@example.test`;
    await apiCall(owner, "POST", "/api/v1/invitations", { email, role: "TECHNICIAN" }, { "Idempotency-Key": unique() });
    const invitation = await mailToken(email, "lädt Sie zu ERIKA ein");
    const page = await (await browser.newContext()).newPage();
    const ip = `10.97.${Math.floor(Math.random() * 250)}.${Math.floor(Math.random() * 250) + 1}`;
    await page.request.get("/api/v1/auth/session", { headers: { "X-Forwarded-For": ip } });
    const post = async (path: string, data: unknown) => {
      const cookie = (await page.context().cookies()).find((c) => c.name === "XSRF-TOKEN");
      const response = await page.request.post(path, {
        data,
        headers: { "X-XSRF-TOKEN": decodeURIComponent(cookie?.value ?? ""), "X-Forwarded-For": ip },
      });
      expect(response.ok(), `${path}: ${response.status()} ${await response.text()}`).toBeTruthy();
    };
    await post("/api/v1/auth/signup", {
      email,
      password,
      displayName: name,
      termsAccepted: true,
      workspaceInvitation: invitation,
    });
    await post("/api/v1/auth/verify-email", { token: await mailToken(email, "bestätigen") });
    return { email, page };
  }

  function tomorrow(): string {
    const d = new Date();
    d.setDate(d.getDate() + 1);
    const pad = (n: number) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  }

  test("plan a work order, assign two technicians and move one in the calendar", async ({ page, browser }) => {
    await signedInAccount(page, "e2e-mara");
    const anna = await technician(page, browser, "Anna Geselle");
    await technician(page, browser, "Ben Azubi");

    // Technicians work in the app: the web sign-in tells them so.
    await anna.page.goto("/login");
    await anna.page.getByLabel("E-Mail-Adresse").fill(anna.email);
    await anna.page.getByLabel("Passwort").fill(password);
    await anna.page.getByRole("button", { name: "Weiter" }).click();
    await expect(anna.page.getByRole("main").getByRole("alert")).toContainText("MARA-App");

    // A customer (business) through the customer list.
    const customer = `Wohnbau Petersen ${unique()}`;
    await page.goto("/customers");
    await page.getByTestId("new-customer").click();
    await page.getByLabel("Name / Firma").fill(customer);
    await page.getByLabel("Kundenart").selectOption("BUSINESS");
    await page.getByLabel("Ort").fill("Leipzig");
    await page.getByRole("button", { name: "Speichern" }).click();
    await expect(page.getByTestId("customer-list")).toContainText(customer);

    // The work order: customer, a new site, time, two technicians.
    await page.getByRole("link", { name: "Aufträge" }).click();
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Aufträge");
    await expectAccessible(page);
    await page.getByRole("link", { name: "Neuer Auftrag" }).first().click();
    await page.getByLabel("Kunde", { exact: true }).selectOption({ label: `${customer} (Leipzig)` });
    await page.getByRole("button", { name: "Neuer Einsatzort" }).click();
    await page.getByLabel("Bezeichnung des Einsatzorts").fill("Wohnung 3.2");
    await page.getByLabel("Straße und Hausnummer").fill("Lindenauer Markt 9");
    await page.getByLabel("PLZ").fill("04177");
    await page.getByLabel("Ort", { exact: true }).fill("Leipzig");
    await page.getByRole("button", { name: "Einsatzort anlegen" }).click();
    await expect(page.getByRole("status")).toHaveText("Einsatzort angelegt.");
    await expect(page.getByLabel("Einsatzort", { exact: true })).toHaveValue(/.+/);
    await page.getByLabel("Kurzbeschreibung").fill("Badsanierung");
    const day = tomorrow();
    await page.getByLabel("Datum").fill(day);
    await page.getByLabel("Von").fill("08:00");
    await page.getByLabel("Bis").fill("12:00");
    await page.getByLabel("Anna Geselle").check();
    await page.getByLabel("Ben Azubi").check();
    await expectAccessible(page);
    await page.getByRole("button", { name: "Auftrag anlegen" }).click();
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Badsanierung");
    await expect(page.getByTestId("work-order-status")).toHaveText("Geplant");
    await expect(page.getByText("Lindenauer Markt 9, 04177 Leipzig")).toBeVisible();

    // The calendar shows it in both technicians' columns.
    await page.goto(`/work-orders/calendar?day=${day}`);
    const columnOf = (name: string) => page.getByRole("region", { name, exact: true });
    await expect(columnOf("Anna Geselle").getByRole("link", { name: "Badsanierung" })).toBeVisible();
    await expect(columnOf("Ben Azubi").getByRole("link", { name: "Badsanierung" })).toBeVisible();
    await expectAccessible(page);

    // Drag Ben's card to the owner: Ben is off the job, the owner is on it.
    await columnOf("Ben Azubi").getByTestId("calendar-card").dragTo(columnOf("Erika Test"));
    await expect(page.getByRole("status")).toContainText("ist jetzt bei Erika Test");
    await expect(columnOf("Erika Test").getByRole("link", { name: "Badsanierung" })).toBeVisible();
    await expect(columnOf("Ben Azubi").getByRole("link", { name: "Badsanierung" })).toHaveCount(0);
    await expect(columnOf("Anna Geselle").getByRole("link", { name: "Badsanierung" })).toBeVisible();

    // After a reload the change is still there (it was saved, not only moved on screen).
    await page.reload();
    await expect(columnOf("Erika Test").getByRole("link", { name: "Badsanierung" })).toBeVisible();
    await expect(columnOf("Ben Azubi").getByRole("link", { name: "Badsanierung" })).toHaveCount(0);
  });

  test("a technician's phone shows up for the owner and is locked out when revoked", async ({ page, browser }) => {
    await signedInAccount(page, "e2e-mara-devices");
    const tech = await technician(page, browser, "Dora Monteurin");

    // The phone: 2FA set-up and sign-in through the app API (no cookies, no CSRF; ADR 0037).
    const phone = tech.page.request;
    const credentials = { email: tech.email, password };
    const enrolment = await phone.post("/api/v1/mobile/auth/totp-enrolment", { data: credentials });
    expect(enrolment.status()).toBe(200);
    const secret = ((await enrolment.json()) as { secret: string }).secret;
    const login = await phone.post("/api/v1/mobile/auth/device-login", {
      data: {
        ...credentials,
        code: totp(secret),
        device: { name: "Galaxy XCover 7", platform: "ANDROID", appVersion: "0.2.0" },
      },
    });
    expect(login.status(), await login.text()).toBe(200);
    const accessToken = ((await login.json()) as { tokens: { accessToken: string } }).tokens.accessToken;
    const deviceStatus = () =>
      phone.get("/api/v1/mobile/devices/me/status", { headers: { Authorization: `Bearer ${accessToken}` } });
    expect((await deviceStatus()).status()).toBe(200);

    // The owner sees the phone and signs it out.
    await page.goto("/settings/devices");
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Geräte in der MARA-App");
    const person = page.getByRole("region", { name: /Dora Monteurin/ });
    await expect(person.getByTestId("device-list")).toContainText("Galaxy XCover 7");
    await expectAccessible(page);
    await person.getByRole("button", { name: /Abmelden/ }).click();
    await page.getByRole("button", { name: "Ja, abmelden" }).click();
    await expect(page.getByRole("status")).toHaveText("Galaxy XCover 7 von Dora Monteurin ist abgemeldet.");
    await expect(person.getByTestId("device-revoked")).toContainText("Abgemeldet");

    // The phone is refused at once and told to delete its data.
    const refused = await deviceStatus();
    expect(refused.status()).toBe(401);
    expect(((await refused.json()) as { code: string }).code).toBe("DEVICE_REVOKED");
  });

  test("a status change and notes made on the phone appear in the web app", async ({ page, browser }) => {
    await signedInAccount(page, "e2e-mara-sync");
    const tech = await technician(page, browser, "Emil Monteur");
    const techId = (await apiCall(page, "GET", "/api/v1/staff")).body.items.find(
      (s: { displayName: string }) => s.displayName === "Emil Monteur",
    ).userId as string;
    const customer = (
      await apiCall(
        page,
        "POST",
        "/api/v1/customers",
        { name: `Familie Sommer ${unique()}`, countryCode: "DE", vatTreatment: "DOMESTIC", phone: "0341 987654" },
        { "Idempotency-Key": unique() },
      )
    ).body.id as string;
    const order = (
      await apiCall(
        page,
        "POST",
        "/api/v1/work-orders",
        { customerId: customer, title: "Therme warten", assigneeIds: [techId] },
        { "Idempotency-Key": unique() },
      )
    ).body.id as string;

    // The phone signs in, pulls its work orders and reports progress (api/mobile.yaml, ADR 0038).
    const phone = tech.page.request;
    const credentials = { email: tech.email, password };
    const secret = (
      (await (await phone.post("/api/v1/mobile/auth/totp-enrolment", { data: credentials })).json()) as {
        secret: string;
      }
    ).secret;
    const login = await phone.post("/api/v1/mobile/auth/device-login", {
      data: {
        ...credentials,
        code: totp(secret),
        device: { name: "Pixel 8", platform: "ANDROID", appVersion: "0.3.0" },
      },
    });
    const auth = {
      Authorization: `Bearer ${((await login.json()) as { tokens: { accessToken: string } }).tokens.accessToken}`,
    };
    type Pulled = {
      records: { entity: string; id: string; fieldVersions: Record<string, number>; data: Record<string, unknown> }[];
    };
    const pulled = (await (
      await phone.get("/api/v1/mobile/sync/pull?protocolVersion=1", { headers: auth })
    ).json()) as Pulled;
    const record = pulled.records.find((r) => r.entity === "work_order" && r.id === order);
    expect(record?.data.title).toBe("Therme warten");
    expect(pulled.records.find((r) => r.entity === "customer" && r.id === customer)?.data.phone).toBe("0341 987654");
    const pushed = await phone.post("/api/v1/mobile/sync/push", {
      headers: auth,
      data: {
        protocolVersion: 1,
        mutations: [
          {
            mutationId: crypto.randomUUID(),
            entity: "work_order",
            entityId: order,
            op: "UPDATE",
            values: { status: "IN_PROGRESS", workNotes: "Brenner gereinigt, Abgaswerte ok" },
            baseVersions: { status: record!.fieldVersions.status, workNotes: record!.fieldVersions.workNotes },
          },
        ],
      },
    });
    expect(((await pushed.json()) as { results: { status: string }[] }).results[0]?.status).toBe("APPLIED");

    // The office sees it at once (M-OF-02: within a minute).
    await page.goto(`/work-orders/${order}`);
    await expect(page.getByTestId("work-order-status")).toHaveText("In Arbeit");
    await expect(page.getByTestId("work-notes")).toHaveText("Brenner gereinigt, Abgaswerte ok");
    await expect(page.getByRole("heading", { name: "Aus der MARA-App", exact: true })).toBeVisible();
    await expectAccessible(page);
  });

  /** The ID two phones derive alike (mara-rules/vectors/ids/derived.json). */
  function derivedId(namespace: string, name: string): string {
    const ns = Buffer.from(namespace.replaceAll("-", ""), "hex");
    const bytes = createHash("sha256")
      .update(Buffer.concat([ns, Buffer.from(name, "utf8")]))
      .digest()
      .subarray(0, 16);
    bytes[6] = 0x80 | (bytes[6]! & 0x0f);
    bytes[8] = 0x80 | (bytes[8]! & 0x3f);
    const hex = bytes.toString("hex");
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }

  test("a report recorded on the phone appears on the work order page", async ({ page, browser }) => {
    await signedInAccount(page, "e2e-mara-report");
    const tech = await technician(page, browser, "Frieda Fliesenlegerin");
    const techId = (await apiCall(page, "GET", "/api/v1/staff")).body.items.find(
      (s: { displayName: string }) => s.displayName === "Frieda Fliesenlegerin",
    ).userId as string;
    const customer = (
      await apiCall(
        page,
        "POST",
        "/api/v1/customers",
        { name: `Familie Winter ${unique()}`, countryCode: "DE", vatTreatment: "DOMESTIC" },
        { "Idempotency-Key": unique() },
      )
    ).body.id as string;
    const template = (
      await apiCall(
        page,
        "POST",
        "/api/v1/checklist-templates",
        {
          name: "Fliesen",
          items: [{ key: "abdichtung", label: "Abdichtung geprüft", kind: "YES_NO", required: true }],
        },
        { "Idempotency-Key": unique() },
      )
    ).body.id as string;
    const order = (
      await apiCall(
        page,
        "POST",
        "/api/v1/work-orders",
        { customerId: customer, title: "Bad fliesen", assigneeIds: [techId], checklistTemplateId: template },
        { "Idempotency-Key": unique() },
      )
    ).body.id as string;

    const phone = tech.page.request;
    const credentials = { email: tech.email, password };
    const secret = (
      (await (await phone.post("/api/v1/mobile/auth/totp-enrolment", { data: credentials })).json()) as {
        secret: string;
      }
    ).secret;
    const login = await phone.post("/api/v1/mobile/auth/device-login", {
      data: {
        ...credentials,
        code: totp(secret),
        device: { name: "Pixel 8", platform: "ANDROID", appVersion: "0.4.0" },
      },
    });
    const auth = {
      Authorization: `Bearer ${((await login.json()) as { tokens: { accessToken: string } }).tokens.accessToken}`,
    };

    // On site, offline, then sent in one push (M-MO-03, M-MO-04, M-MO-06 to M-MO-08).
    const report = derivedId(order, "report/1");
    const mutation = (entity: string, entityId: string, values: Record<string, unknown>, op = "CREATE") => ({
      mutationId: crypto.randomUUID(),
      entity,
      entityId,
      op,
      values,
    });
    const started = new Date(Date.now() - 2 * 3600_000);
    started.setSeconds(0, 0);
    const pushed = await phone.post("/api/v1/mobile/sync/push", {
      headers: auth,
      data: {
        protocolVersion: 1,
        mutations: [
          mutation("report", report, { workOrderId: order }),
          mutation("time_entry", crypto.randomUUID(), {
            reportId: report,
            technicianId: techId,
            kind: "WORK",
            startedAt: started.toISOString(),
            endedAt: new Date(started.getTime() + 95 * 60_000).toISOString(),
          }),
          mutation("material_line", crypto.randomUUID(), {
            reportId: report,
            description: "Fliesenkleber C2",
            quantity: "2.5",
            unitCode: "KGM",
          }),
          mutation("aufmass_position", crypto.randomUUID(), {
            reportId: report,
            label: "Wand Dusche",
            kind: "AREA",
            length: "2.4",
            width: "2.1",
            deductions: [{ label: "Fenster", length: "0.6", width: "0.8" }],
            unitCode: "MTK",
          }),
          mutation("checklist_answer", derivedId(report, "checklist/abdichtung"), {
            reportId: report,
            itemKey: "abdichtung",
            value: "YES",
          }),
          mutation("report", report, { workDescription: "Wand gefliest, verfugt.", state: "COMPLETED" }, "UPDATE"),
        ],
      },
    });
    const results = ((await pushed.json()) as { results: { status: string; code?: string }[] }).results;
    expect(results.map((r) => r.code ?? r.status)).toEqual(Array(6).fill("APPLIED"));

    await page.goto(`/work-orders/${order}`);
    const section = page.getByTestId("report");
    await expect(section.getByRole("heading", { name: "Bericht aus der MARA-App" })).toBeVisible();
    await expect(page.getByTestId("report-state")).toHaveText("Abgeschlossen");
    await expect(page.getByTestId("report-times")).toContainText("Frieda Fliesenlegerin");
    await expect(page.getByTestId("report-times")).toContainText("1:35 h");
    await expect(page.getByTestId("report-materials")).toContainText("2,5 kg");
    await expect(page.getByTestId("report-aufmass")).toContainText("4,560 m²");
    await expect(page.getByTestId("report-checklist")).toContainText("Ja");
    await expect(page.getByTestId("report-description")).toHaveText("Wand gefliest, verfugt.");
    await expectAccessible(page);
  });

  test("staff levels, staff settings and checklist templates", async ({ page, browser }) => {
    await signedInAccount(page, "e2e-mara-settings");
    await technician(page, browser, "Carla Monteurin");

    await page.goto("/settings/staff");
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Mitarbeitende und Stundensätze");
    await page.getByRole("button", { name: "Neuer Stundensatz" }).click();
    await page.getByLabel("Bezeichnung").fill("Geselle");
    await page.getByLabel("Stundensatz netto (€)").fill("58,50");
    await page.getByRole("button", { name: "Speichern" }).click();
    await expect(page.getByTestId("staff-levels")).toContainText("Geselle");
    await expect(page.getByTestId("staff-levels")).toContainText("58.50 €");
    await page.getByLabel("Stundensatz von Carla Monteurin").selectOption({ label: "Geselle (58.50 €)" });
    await expect(page.getByRole("status")).toHaveText("Gespeichert.");
    await expectAccessible(page);
    await page.reload();
    await expect(page.getByLabel("Stundensatz von Carla Monteurin")).toHaveValue(/.+/);

    await page.goto("/settings/checklists");
    await page.getByRole("button", { name: "Neue Checkliste" }).click();
    await page.getByLabel("Name der Checkliste").fill("Wartung Gasheizung");
    await page.getByLabel("Text von Punkt 1").fill("Abgasmessung durchgeführt");
    await page.getByRole("button", { name: "Punkt hinzufügen" }).click();
    await page.getByLabel("Text von Punkt 2").fill("CO-Wert");
    await page.getByLabel("Art von Punkt 2").selectOption("VALUE");
    await expectAccessible(page);
    await page.getByRole("button", { name: "Speichern" }).click();
    await expect(page.getByTestId("checklist-list")).toContainText("Wartung Gasheizung");
    await expect(page.getByTestId("checklist-list")).toContainText("2 Punkte");
  });
});
