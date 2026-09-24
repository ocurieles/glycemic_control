package com.ingeint.checkin.data.remote

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

/** Errores de un callable, con el código tal como lo manda `HttpsError` (docs/04). */
class FunctionsCallError(val code: String, message: String) : Exception(message)

/**
 * Envoltorio de los callables de familia/vinculación (docs/04). Traduce
 * `FirebaseFunctionsException` a [FunctionsCallError] para que la UI pueda
 * mostrar mensajes según el código, sin acoplarse al SDK de Functions.
 */
class FunctionsApi(private val functions: FirebaseFunctions) {
    data class CreateFamilyResult(val familyId: String, val code: String, val expiresAt: Long)

    data class JoinFamilyResult(val familyId: String, val childName: String, val role: String)

    data class PairingCodeResult(val code: String, val expiresAt: Long)

    suspend fun createFamily(childName: String, parentName: String): CreateFamilyResult =
        call("createFamily", mapOf("childName" to childName, "parentName" to parentName)) { data ->
            CreateFamilyResult(
                familyId = data["familyId"] as String,
                code = data["code"] as String,
                expiresAt = (data["expiresAt"] as Number).toLong(),
            )
        }

    suspend fun createPairingCode(): PairingCodeResult =
        call("createPairingCode", emptyMap()) { data ->
            PairingCodeResult(code = data["code"] as String, expiresAt = (data["expiresAt"] as Number).toLong())
        }

    suspend fun joinFamily(code: String, role: String, displayName: String?): JoinFamilyResult =
        call(
            "joinFamily",
            buildMap {
                put("code", code)
                put("role", role)
                if (displayName != null) put("displayName", displayName)
            },
        ) { data ->
            JoinFamilyResult(
                familyId = data["familyId"] as String,
                childName = data["childName"] as String,
                role = data["role"] as String,
            )
        }

    suspend fun leaveFamily() {
        call<Unit>("leaveFamily", emptyMap()) {}
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> call(name: String, data: Map<String, Any?>, map: (Map<String, Any?>) -> T): T {
        try {
            val result = functions.getHttpsCallable(name).call(data).await()
            return map(result.data as? Map<String, Any?> ?: emptyMap())
        } catch (e: FirebaseFunctionsException) {
            throw FunctionsCallError(e.code.name, e.message ?: "Error al llamar a $name")
        }
    }
}
