package com.ingeint.checkin.push

import com.google.firebase.firestore.FieldValue
import com.google.firebase.messaging.FirebaseMessaging
import com.ingeint.checkin.AppContainer
import com.ingeint.checkin.BuildConfig
import kotlinx.coroutines.tasks.await

/**
 * Guarda el token FCM actual en `users/{uid}` (docs/04 "Token FCM").
 *
 * BUG real encontrado en pruebas de campo (docs/08 F8): `PushService.onNewToken`
 * es la única vía anterior para guardar el token, pero Firebase genera el token
 * al arrancar el proceso — casi siempre **antes** de que exista `users/{uid}`
 * (lo crea `createFamily`/`joinFamily` del lado del servidor). El `.update()`
 * fallaba con NOT_FOUND, quedaba silenciado por el `onFailure { Log.w(...) }`,
 * y como `onNewToken` no vuelve a dispararse hasta que el token rote (algo
 * infrecuente), el token nunca se guardaba: los padres/el niño no recibían
 * ningún push. Por eso también se llama aquí, explícitamente, justo después de
 * vincularse (cuando `users/{uid}` ya existe seguro) y en cada arranque de la
 * app para los teléfonos ya vinculados (cubre los que se instalaron antes de
 * este arreglo).
 */
suspend fun syncFcmToken(container: AppContainer) {
    val uid = container.auth.currentUser?.uid ?: return
    runCatching {
        val token = FirebaseMessaging.getInstance().token.await()
        container.firestore
            .collection("users")
            .document(uid)
            .update(
                mapOf(
                    "fcmToken" to token,
                    "tokenUpdatedAt" to FieldValue.serverTimestamp(),
                    "appVersion" to BuildConfig.VERSION_NAME,
                ),
            )
            .await()
    }
}
