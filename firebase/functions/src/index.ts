import { initializeApp } from "firebase-admin/app";

initializeApp();

import "./config";

export { createFamily, createPairingCode, joinFamily, leaveFamily } from "./families";
export { onEventCreated } from "./events";
export { onFamilyUpdated } from "./settings";
export { removeLibreLinkUp, setLibreLinkUp, testLibreLinkUp } from "./libre";
export { checkMissedSlots } from "./missed";
export { backfillInsulinDaysCallable as backfillInsulinDays } from "./insulin";
