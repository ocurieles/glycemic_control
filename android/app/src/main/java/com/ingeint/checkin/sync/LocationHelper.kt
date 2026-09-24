package com.ingeint.checkin.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

private const val LOCATION_TIMEOUT_MS = 5_000L

/** Ubicación para el SOS: timeout de 5 s, nunca bloquea el envío (docs/06, docs/07). */
object LocationHelper {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    suspend fun getCurrentLocationOrNull(context: Context): Location? {
        if (!hasPermission(context)) return null
        return try {
            withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                val client = LocationServices.getFusedLocationProviderClient(context)
                val request = CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY).build()
                @Suppress("MissingPermission") // ya se verificó hasPermission() arriba
                client.getCurrentLocation(request, null).await()
            }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }
}
