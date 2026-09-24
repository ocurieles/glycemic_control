package com.ingeint.checkin

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.google.firebase.functions.functions
import com.google.firebase.messaging.messaging
import com.ingeint.checkin.data.local.Prefs
import com.ingeint.checkin.data.remote.FunctionsApi

private const val FUNCTIONS_REGION = "us-east1" // docs/02 D3
private const val EMULATOR_HOST = "10.0.2.2" // docs/06: host del emulador Android hacia el host

/**
 * DI manual (docs/06 "DI manual: un AppContainer en CheckinApp. No se usa Hilt").
 * En debug, si `BuildConfig.USE_EMULATORS`, todo apunta a los emuladores de Firebase.
 */
class AppContainer(context: Context) {
    /** Contexto de aplicación, para llamadas puntuales que lo necesitan (p. ej. [com.ingeint.checkin.notify.Channels]). */
    val appContextForChannels: Context = context.applicationContext

    val auth = Firebase.auth
    val firestore = Firebase.firestore
    val functions = Firebase.functions(FUNCTIONS_REGION)
    val messaging = Firebase.messaging

    init {
        if (BuildConfig.USE_EMULATORS) {
            auth.useEmulator(EMULATOR_HOST, 9099)
            firestore.useEmulator(EMULATOR_HOST, 8080)
            functions.useEmulator(EMULATOR_HOST, 5001)
        }
    }

    val prefs = Prefs(context.applicationContext)
    val functionsApi = FunctionsApi(functions)
}
