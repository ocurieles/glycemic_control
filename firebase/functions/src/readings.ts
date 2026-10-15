import { getFirestore } from "firebase-admin/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions";
import { lluAppVersion, lluEncKey } from "./config";
import { resolveSettings } from "./families";
import { fetchRecentReadings } from "./libre/service";
import { ReminderSettings } from "./schedule";
import { dateKeyOf } from "./time";

export interface StoredReading {
  v: number;
  tr?: number;
}

interface ReadingInput {
  valueMgDl: number;
  trend?: number;
  factoryTimestampMs: number;
}

/**
 * Pura: agrupa lecturas por día local. La clave de cada lectura es su instante en segundos
 * (como texto), así reescribir una lectura ya guardada no la duplica.
 */
export function groupReadingsByDay(items: ReadingInput[], timezone: string): Map<string, Record<string, StoredReading>> {
  const byDay = new Map<string, Record<string, StoredReading>>();
  for (const item of items) {
    if (!Number.isFinite(item.factoryTimestampMs) || !Number.isFinite(item.valueMgDl)) continue;
    const day = dateKeyOf(item.factoryTimestampMs, timezone);
    const readings = byDay.get(day) ?? {};
    readings[String(Math.floor(item.factoryTimestampMs / 1000))] = {
      v: item.valueMgDl,
      ...(item.trend !== undefined ? { tr: item.trend } : {}),
    };
    byDay.set(day, readings);
  }
  return byDay;
}

/** Guarda las lecturas en `families/{fid}/glucoseDays/{fecha}.readings` (merge, idempotente). */
export async function storeReadings(familyId: string, items: ReadingInput[], timezone: string): Promise<number> {
  const db = getFirestore();
  const byDay = groupReadingsByDay(items, timezone);
  await Promise.all(
    [...byDay].map(([day, readings]) =>
      db.doc(`families/${familyId}/glucoseDays/${day}`).set({ date: day, readings }, { merge: true }),
    ),
  );
  return items.length;
}

/** Lectura automática de glicemia cada 15 min para las familias con LibreLinkUp conectado (pedido 2026-10-16). */
export const recordGlucoseReadings = onSchedule(
  { schedule: "every 15 minutes", timeZone: "America/Caracas", secrets: [lluEncKey], timeoutSeconds: 120 },
  async () => {
    const familiesSnap = await getFirestore().collection("families").where("libreConfigured", "==", true).get();
    for (const familyDoc of familiesSnap.docs) {
      const settings = resolveSettings(familyDoc.data().settings as Partial<ReminderSettings> | undefined);
      const result = await fetchRecentReadings(familyDoc.id, lluAppVersion.value(), lluEncKey.value());
      if ("error" in result) {
        logger.info("lectura periódica sin datos", { familyId: familyDoc.id, error: result.error });
        continue;
      }
      const stored = await storeReadings(familyDoc.id, result.items, settings.timezone);
      logger.info("lecturas guardadas", { familyId: familyDoc.id, stored });
    }
  },
);
