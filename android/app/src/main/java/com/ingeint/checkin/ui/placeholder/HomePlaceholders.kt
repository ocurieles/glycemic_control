package com.ingeint.checkin.ui.placeholder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ingeint.checkin.R

/**
 * Placeholder de padres: el resto de la pantalla (cumplimiento, mensajes, SOS…) llega
 * en F5. La del niño ya es real desde F3 (ver [com.ingeint.checkin.ui.child.ChildScreen]).
 */
@Composable
fun ParentHomeScreen(childName: String, onOpenDiagnostics: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.parent_home_placeholder_title, childName),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.parent_home_placeholder_body, childName),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        IconButton(onClick = onOpenDiagnostics, modifier = Modifier.padding(8.dp).align(Alignment.TopEnd)) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_icon_description))
        }
    }
}
