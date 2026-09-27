import { FieldValue, getFirestore } from "firebase-admin/firestore";

/**
 * Acumula las dosis de insulina por día en `families/{fid}/days/{fecha}.insulin`
 * (pedido del usuario 2026-09-24: vista de calendario en la app del padre). Separado
 * de `recompute.ts`/`compliance.ts` a propósito: eso es sobre el cumplimiento de
 * horario de revisiones, esto es solo una suma aditiva, mucho más simple.
 */
export async function addInsulinDose(
  familyId: string,
  dateKey: string,
  eventId: string,
  atMillis: number,
  units: number,
): Promise<void> {
  const dayRef = getFirestore().doc(`families/${familyId}/days/${dateKey}`);
  await dayRef.set(
    {
      date: dateKey,
      insulin: {
        total: FieldValue.increment(units),
        doses: FieldValue.arrayUnion({ eventId, atMillis, units }),
      },
    },
    { merge: true },
  );
}
