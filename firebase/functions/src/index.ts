import { initializeApp } from "firebase-admin/app";

initializeApp();

import "./config";

export { createFamily, createPairingCode, joinFamily, leaveFamily } from "./families";
export { onEventCreated } from "./events";
export { onFamilyUpdated } from "./settings";
