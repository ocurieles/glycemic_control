package com.ingeint.checkin

import android.app.NotificationManager
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ingeint.checkin.notify.ChannelIds
import com.ingeint.checkin.notify.Channels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CLAUDE.md regla 2 / docs/06 "Reglas de discreción #1" y "#6": los canales
 * `child_*` deben crearse SIEMPRE con `sound == null` y sin vibración propia del
 * canal (la vibración la maneja Haptics). Este test NO SE DESACTIVA.
 */
@RunWith(AndroidJUnit4::class)
class ChildChannelsInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val manager = context.getSystemService<NotificationManager>()!!

    @Test
    fun childChannelsHaveNoSoundAndNoOwnVibration() {
        Channels.createForRole(context, "child")

        for (id in listOf(ChannelIds.CHILD_REMINDER, ChannelIds.CHILD_MESSAGE)) {
            val channel = manager.getNotificationChannel(id)
            assertNull("El canal $id no debería tener sonido", channel?.sound)
            assertFalse("El canal $id no debería vibrar por sí mismo (lo hace Haptics)", channel!!.shouldVibrate())
        }
    }

    @Test
    fun childRoleDoesNotCreateParentChannels() {
        Channels.deleteAll(context)
        Channels.createForRole(context, "child")

        for (id in listOf(ChannelIds.PARENT_CHECKIN, ChannelIds.PARENT_ALERT, ChannelIds.PARENT_SOS)) {
            assertNull("El rol niño no debería crear el canal $id", manager.getNotificationChannel(id))
        }
    }

    @Test
    fun syncStatusChannelIsCreatedForBothRoles() {
        Channels.createForRole(context, "child")
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(ChannelIds.SYNC_STATUS)?.importance,
        )
    }
}
