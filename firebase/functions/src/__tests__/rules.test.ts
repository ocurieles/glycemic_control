import { readFileSync } from "node:fs";
import { join } from "node:path";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  RulesTestEnvironment,
} from "@firebase/rules-unit-testing";
import { serverTimestamp, Timestamp } from "firebase/firestore";
import { afterAll, afterEach, beforeAll, describe, it } from "vitest";

/**
 * Pruebas de firestore.rules (docs/03 §4) contra el emulador de Firestore.
 * Correr con: `firebase emulators:exec "npm --prefix functions test"` desde firebase/.
 */

const rulesPath = join(__dirname, "..", "..", "..", "firestore.rules");
const rules = readFileSync(rulesPath, "utf-8");

const FAMILY_ID = "fam1";
const PARENT_UID = "parent1";
const OTHER_PARENT_UID = "parent2";
const CHILD_UID = "child1";
const OUTSIDER_UID = "outsider";

const validSettings = {
  enabled: true,
  intervalMinutes: 10,
  days: [1, 2, 3, 4, 5],
  startTime: "07:00",
  endTime: "13:00",
  escalationMinutes: 5,
  nudgeMinutes: 3,
  timezone: "America/Caracas",
  lowThreshold: 70,
  highThreshold: 180,
  smsFallbackEnabled: false,
  smsNumbers: [],
  childPhone: null,
};

let testEnv: RulesTestEnvironment;

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: "demo-checkin-rules",
    firestore: { rules, host: "127.0.0.1", port: 8080 },
  });
});

afterEach(async () => {
  await testEnv.clearFirestore();
});

afterAll(async () => {
  await testEnv.cleanup();
});

async function seedFamily() {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.doc(`families/${FAMILY_ID}`).set({
      childName: "Cesar",
      childUid: CHILD_UID,
      parents: { [PARENT_UID]: { name: "Mamá" }, [OTHER_PARENT_UID]: { name: "Papá" } },
      settings: validSettings,
      libreConfigured: false,
      createdAt: Timestamp.now(),
    });
  });
}

function asParent(uid = PARENT_UID) {
  return testEnv.authenticatedContext(uid, { familyId: FAMILY_ID, role: "parent" }).firestore();
}

function asChild(uid = CHILD_UID) {
  return testEnv.authenticatedContext(uid, { familyId: FAMILY_ID, role: "child" }).firestore();
}

function asOutsider() {
  return testEnv.authenticatedContext(OUTSIDER_UID, { familyId: "otra-familia", role: "parent" }).firestore();
}

function asAnonymous() {
  return testEnv.unauthenticatedContext().firestore();
}

describe("families/{fid}", () => {
  it("un miembro puede leer su familia", async () => {
    await seedFamily();
    await assertSucceeds(asParent().doc(`families/${FAMILY_ID}`).get());
    await assertSucceeds(asChild().doc(`families/${FAMILY_ID}`).get());
  });

  it("alguien fuera de la familia no puede leerla", async () => {
    await seedFamily();
    await assertFails(asOutsider().doc(`families/${FAMILY_ID}`).get());
    await assertFails(asAnonymous().doc(`families/${FAMILY_ID}`).get());
  });

  it("un padre puede actualizar settings válidos", async () => {
    await seedFamily();
    await assertSucceeds(
      asParent()
        .doc(`families/${FAMILY_ID}`)
        .update({ settings: { ...validSettings, intervalMinutes: 15 } }),
    );
  });

  it("el niño no puede actualizar settings", async () => {
    await seedFamily();
    await assertFails(
      asChild()
        .doc(`families/${FAMILY_ID}`)
        .update({ settings: { ...validSettings, intervalMinutes: 15 } }),
    );
  });

  it("rechaza settings inválidos (intervalMinutes fuera de rango)", async () => {
    await seedFamily();
    await assertFails(
      asParent()
        .doc(`families/${FAMILY_ID}`)
        .update({ settings: { ...validSettings, intervalMinutes: 999 } }),
    );
  });

  it("rechaza settings inválidos (endTime <= startTime)", async () => {
    await seedFamily();
    await assertFails(
      asParent()
        .doc(`families/${FAMILY_ID}`)
        .update({ settings: { ...validSettings, startTime: "13:00", endTime: "07:00" } }),
    );
  });

  it("un padre no puede tocar libreConfigured directamente", async () => {
    await seedFamily();
    await assertFails(asParent().doc(`families/${FAMILY_ID}`).update({ libreConfigured: true }));
  });
});

describe("families/{fid}/events/{eid}", () => {
  it("el niño puede crear un checkin válido", async () => {
    await seedFamily();
    await assertSucceeds(
      asChild()
        .doc(`families/${FAMILY_ID}/events/e1`)
        .set({
          type: "checkin",
          createdBy: CHILD_UID,
          createdAt: serverTimestamp(),
          clientAt: Timestamp.now(),
          source: "app",
        }),
    );
  });

  it("el niño puede crear un insulin_dose con una dosis permitida", async () => {
    await seedFamily();
    await assertSucceeds(
      asChild()
        .doc(`families/${FAMILY_ID}/events/e-dose1`)
        .set({
          type: "insulin_dose",
          createdBy: CHILD_UID,
          createdAt: serverTimestamp(),
          clientAt: Timestamp.now(),
          source: "app",
          doseUnits: 1.5,
        }),
    );
  });

  it("rechaza un insulin_dose con una dosis fuera de las permitidas", async () => {
    await seedFamily();
    await assertFails(
      asChild()
        .doc(`families/${FAMILY_ID}/events/e-dose2`)
        .set({
          type: "insulin_dose",
          createdBy: CHILD_UID,
          createdAt: serverTimestamp(),
          clientAt: Timestamp.now(),
          source: "app",
          doseUnits: 4,
        }),
    );
  });

  it("rechaza un insulin_dose sin doseUnits", async () => {
    await seedFamily();
    await assertFails(
      asChild()
        .doc(`families/${FAMILY_ID}/events/e-dose3`)
        .set({
          type: "insulin_dose",
          createdBy: CHILD_UID,
          createdAt: serverTimestamp(),
          clientAt: Timestamp.now(),
          source: "app",
        }),
    );
  });

  it("el niño no puede crear un parent_message", async () => {
    await seedFamily();
    await assertFails(
      asChild()
        .doc(`families/${FAMILY_ID}/events/e2`)
        .set({
          type: "parent_message",
          createdBy: CHILD_UID,
          createdAt: serverTimestamp(),
          clientAt: Timestamp.now(),
          source: "app",
        }),
    );
  });

  it("un padre puede crear un parent_message pero no un checkin", async () => {
    await seedFamily();
    await assertSucceeds(
      asParent()
        .doc(`families/${FAMILY_ID}/events/e3`)
        .set({
          type: "parent_message",
          createdBy: PARENT_UID,
          createdAt: serverTimestamp(),
          clientAt: Timestamp.now(),
          source: "app",
          text: "Recuerda la lectura",
        }),
    );
    await assertFails(
      asParent()
        .doc(`families/${FAMILY_ID}/events/e4`)
        .set({
          type: "checkin",
          createdBy: PARENT_UID,
          createdAt: serverTimestamp(),
          clientAt: Timestamp.now(),
          source: "app",
        }),
    );
  });

  it("rechaza createdBy distinto de request.auth.uid", async () => {
    await seedFamily();
    await assertFails(
      asChild()
        .doc(`families/${FAMILY_ID}/events/e5`)
        .set({
          type: "checkin",
          createdBy: OUTSIDER_UID,
          createdAt: serverTimestamp(),
          clientAt: Timestamp.now(),
          source: "app",
        }),
    );
  });

  it("rechaza clientAt más de 24h en el futuro", async () => {
    await seedFamily();
    await assertFails(
      asChild()
        .doc(`families/${FAMILY_ID}/events/e6`)
        .set({
          type: "checkin",
          createdBy: CHILD_UID,
          createdAt: serverTimestamp(),
          clientAt: Timestamp.fromMillis(Date.now() + 25 * 3600 * 1000),
          source: "app",
        }),
    );
  });

  it("rechaza clientAt más de 7 días en el pasado", async () => {
    await seedFamily();
    await assertFails(
      asChild()
        .doc(`families/${FAMILY_ID}/events/e7`)
        .set({
          type: "checkin",
          createdBy: CHILD_UID,
          createdAt: serverTimestamp(),
          clientAt: Timestamp.fromMillis(Date.now() - 8 * 24 * 3600 * 1000),
          source: "app",
        }),
    );
  });

  it("rechaza claves extra en el evento", async () => {
    await seedFamily();
    await assertFails(
      asChild()
        .doc(`families/${FAMILY_ID}/events/e8`)
        .set({
          type: "checkin",
          createdBy: CHILD_UID,
          createdAt: serverTimestamp(),
          clientAt: Timestamp.now(),
          source: "app",
          glucose: { valueMgDl: 100 },
        }),
    );
  });

  it("rechaza texto de más de 120 caracteres", async () => {
    await seedFamily();
    await assertFails(
      asParent()
        .doc(`families/${FAMILY_ID}/events/e9`)
        .set({
          type: "parent_message",
          createdBy: PARENT_UID,
          createdAt: serverTimestamp(),
          clientAt: Timestamp.now(),
          source: "app",
          text: "x".repeat(121),
        }),
    );
  });

  it("nadie puede actualizar o borrar un evento (solo Admin SDK)", async () => {
    await seedFamily();
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await ctx
        .firestore()
        .doc(`families/${FAMILY_ID}/events/e10`)
        .set({ type: "checkin", createdBy: CHILD_UID, createdAt: Timestamp.now(), clientAt: Timestamp.now() });
    });
    await assertFails(asChild().doc(`families/${FAMILY_ID}/events/e10`).update({ type: "sos" }));
    await assertFails(asChild().doc(`families/${FAMILY_ID}/events/e10`).delete());
  });

  it("un miembro puede leer los eventos de su familia", async () => {
    await seedFamily();
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await ctx
        .firestore()
        .doc(`families/${FAMILY_ID}/events/e11`)
        .set({ type: "checkin", createdBy: CHILD_UID, createdAt: Timestamp.now(), clientAt: Timestamp.now() });
    });
    await assertSucceeds(asParent().doc(`families/${FAMILY_ID}/events/e11`).get());
    await assertFails(asOutsider().doc(`families/${FAMILY_ID}/events/e11`).get());
  });
});

describe("colecciones exclusivas de Functions", () => {
  it("nadie puede leer ni escribir private/, meta/, pairingCodes/ ni rateLimits/", async () => {
    await seedFamily();
    await assertFails(asParent().doc(`families/${FAMILY_ID}/private/libre`).get());
    await assertFails(asParent().doc(`families/${FAMILY_ID}/meta/pushBuffer`).get());
    await assertFails(asParent().doc(`pairingCodes/123456`).get());
    await assertFails(asParent().doc(`rateLimits/${PARENT_UID}`).get());
  });

  it("un miembro puede leer days/ pero no escribirlo", async () => {
    await seedFamily();
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().doc(`families/${FAMILY_ID}/days/2026-09-21`).set({ date: "2026-09-21" });
    });
    await assertSucceeds(asParent().doc(`families/${FAMILY_ID}/days/2026-09-21`).get());
    await assertFails(asParent().doc(`families/${FAMILY_ID}/days/2026-09-21`).update({ date: "x" }));
  });
});

describe("users/{uid}", () => {
  it("un usuario puede leer y actualizar su propio fcmToken", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().doc(`users/${PARENT_UID}`).set({ familyId: FAMILY_ID, role: "parent" });
    });
    await assertSucceeds(asParent().doc(`users/${PARENT_UID}`).get());
    await assertSucceeds(
      asParent().doc(`users/${PARENT_UID}`).update({ fcmToken: "tok", tokenUpdatedAt: Timestamp.now() }),
    );
  });

  it("un usuario no puede leer el doc de otro ni tocar su rol/familyId", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().doc(`users/${OTHER_PARENT_UID}`).set({ familyId: FAMILY_ID, role: "parent" });
    });
    await assertFails(asParent().doc(`users/${OTHER_PARENT_UID}`).get());
    await assertFails(asParent(OTHER_PARENT_UID).doc(`users/${OTHER_PARENT_UID}`).update({ role: "child" }));
  });
});
