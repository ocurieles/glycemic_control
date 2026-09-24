import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchConnections, fetchGraph, LibreApiError, LibreUnauthorizedError, login } from "../libre/client";

/**
 * Cliente de LibreLinkUp (docs/05) contra un `fetch` mockeado — nunca contra la
 * API real. Cubre los casos documentados: login normal, redirección regional,
 * credenciales inválidas (`status:2`), términos pendientes (`status:4`), versión
 * vieja (HTTP 403), sesión vencida (HTTP 401) y el parseo de `connections`/`graph`.
 */

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response;
}

describe("libre/client", () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("login exitoso devuelve token, accountId (sha256) y sin región", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, {
        status: 0,
        data: { authTicket: { token: "tok-1", expires: 1_700_000_000 }, user: { id: "user-42" } },
      }),
    );

    const result = await login("a@b.com", "secret", "4.16.0");

    expect(result.token).toBe("tok-1");
    expect(result.expiresAtMs).toBe(1_700_000_000_000);
    expect(result.region).toBeNull();
    // sha256("user-42") en hex, calculado independientemente.
    expect(result.accountId).toMatch(/^[0-9a-f]{64}$/);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe("https://api.libreview.io/llu/auth/login");
  });

  it("sigue la redirección regional y reintenta el login ahí", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { status: 0, data: { redirect: true, region: "de" } }))
      .mockResolvedValueOnce(
        jsonResponse(200, {
          status: 0,
          data: { authTicket: { token: "tok-de", expires: 1_700_000_100 }, user: { id: "user-42" } },
        }),
      );

    const result = await login("a@b.com", "secret", "4.16.0");

    expect(result.token).toBe("tok-de");
    expect(result.region).toBe("de");
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1][0]).toBe("https://api-de.libreview.io/llu/auth/login");
  });

  it("status 2 -> LibreApiError('auth')", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { status: 2, error: { message: "notAuthenticated" } }));

    await expect(login("a@b.com", "wrong", "4.16.0")).rejects.toMatchObject({
      code: "auth",
    } satisfies Partial<LibreApiError>);
  });

  it("status 4 -> LibreApiError('terms')", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { status: 4, data: { step: { componentName: "tou" } } }));

    await expect(login("a@b.com", "secret", "4.16.0")).rejects.toMatchObject({ code: "terms" });
  });

  it("HTTP 403 -> LibreApiError('version')", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(403, {}));

    await expect(login("a@b.com", "secret", "0.0.1")).rejects.toMatchObject({ code: "version" });
  });

  it("fetchConnections mapea patientId, nombre y glucosa (FactoryTimestamp UTC)", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, {
        data: [
          {
            patientId: "p1",
            firstName: "Cesar",
            lastName: "R",
            glucoseMeasurement: { ValueInMgPerDl: 110, TrendArrow: 3, FactoryTimestamp: "9/24/2026 2:30:00 PM" },
          },
        ],
      }),
    );

    const [connection] = await fetchConnections({ token: "t", accountId: "acc", region: null }, "4.16.0");

    expect(connection.patientId).toBe("p1");
    expect(connection.name).toBe("Cesar R");
    expect(connection.glucose?.valueMgDl).toBe(110);
    expect(connection.glucose?.trend).toBe(3);
    // 2026-09-24T14:30:00Z en epoch ms.
    expect(connection.glucose?.factoryTimestampMs).toBe(Date.UTC(2026, 8, 24, 14, 30, 0));

    const [, options] = fetchMock.mock.calls[0];
    expect(options.headers.Authorization).toBe("Bearer t");
    expect(options.headers["Account-Id"]).toBe("acc");
  });

  it("HTTP 401 en connections -> LibreUnauthorizedError", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(401, {}));

    await expect(fetchConnections({ token: "t", accountId: "acc", region: null }, "4.16.0")).rejects.toBeInstanceOf(
      LibreUnauthorizedError,
    );
  });

  it("fetchGraph mapea graphData a la misma forma que connections", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, {
        data: {
          graphData: [{ ValueInMgPerDl: 95, TrendArrow: 4, FactoryTimestamp: "9/24/2026 8:00:00 AM" }],
        },
      }),
    );

    const items = await fetchGraph({ token: "t", accountId: "acc", region: "us" }, "4.16.0", "p1");

    expect(items).toHaveLength(1);
    expect(items[0].valueMgDl).toBe(95);
    const [url] = fetchMock.mock.calls[0];
    expect(url).toBe("https://api-us.libreview.io/llu/connections/p1/graph");
  });
});
