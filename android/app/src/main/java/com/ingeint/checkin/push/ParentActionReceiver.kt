package com.ingeint.checkin.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ingeint.checkin.CheckinApp
import com.ingeint.checkin.data.local.OutboxEventType
import com.ingeint.checkin.notify.ParentNotifier
import com.ingeint.checkin.sync.SyncWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Acción "Voy en camino" de la notificación de SOS (docs/01 H5). */
class ParentActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SOS_ACK = "com.ingeint.checkin.action.SOS_ACK"
        const val EXTRA_SOS_EVENT_ID = "sos_event_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SOS_ACK) return
        val sosEventId = intent.getStringExtra(EXTRA_SOS_EVENT_ID) ?: return
        val app = context.applicationContext as CheckinApp
        val pendingResult = goAsync()
        app.applicationScope.launch {
            try {
                val clientAt = app.container.prefs.correctedNowMillis()
                val offset = app.container.prefs.clockOffsetMs.first()
                app.container.outboxRepository.record(
                    type = OutboxEventType.SOS_ACK,
                    clientAtMillis = clientAt,
                    clockOffsetMs = offset,
                    source = "notification",
                    text = "Voy en camino",
                    replyTo = sosEventId,
                )
                SyncWorker.enqueue(context)
                ParentNotifier.cancelSos(context, sosEventId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
