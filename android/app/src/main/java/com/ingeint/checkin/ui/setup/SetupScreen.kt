package com.ingeint.checkin.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ingeint.checkin.R

@Composable
fun SetupScreen(viewModel: SetupViewModel, onLinked: (role: String, childName: String) -> Unit) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.step) {
        (state.step as? SetupStep.Linked)?.let { onLinked(it.role, it.childName) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val step = state.step) {
            SetupStep.ChooseRole ->
                ChooseRoleContent(onParent = viewModel::chooseParent, onChild = viewModel::chooseChild)

            SetupStep.ParentChoice ->
                ParentChoiceContent(
                    onCreate = viewModel::chooseCreateFamily,
                    onJoin = viewModel::chooseJoinFamily,
                    onBack = viewModel::back,
                )

            SetupStep.CreateFamilyForm ->
                CreateFamilyContent(
                    loading = state.loading,
                    error = state.errorMessage,
                    onSubmit = viewModel::createFamily,
                    onBack = viewModel::back,
                )

            is SetupStep.JoinFamilyForm ->
                JoinFamilyContent(
                    role = step.role,
                    loading = state.loading,
                    error = state.errorMessage,
                    onSubmit = { code, displayName -> viewModel.joinFamily(step.role, code, displayName) },
                    onBack = viewModel::back,
                )

            is SetupStep.PairingCodeDisplay ->
                PairingCodeContent(code = step.code, onContinue = viewModel::continueAfterPairingCode)

            is SetupStep.Linked -> Unit // LaunchedEffect de arriba dispara la navegación
        }
    }
}

@Composable
private fun ChooseRoleContent(onParent: () -> Unit, onChild: () -> Unit) {
    Text(
        text = stringResource(R.string.setup_who_title),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(32.dp))
    Button(onClick = onParent, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_role_parent))
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onChild, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_role_child))
    }
}

@Composable
private fun ParentChoiceContent(onCreate: () -> Unit, onJoin: () -> Unit, onBack: () -> Unit) {
    Text(stringResource(R.string.setup_parent_choice_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(32.dp))
    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_create_family_title))
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_join_family_title))
    }
    Spacer(Modifier.height(24.dp))
    OutlinedButton(onClick = onBack) { Text(stringResource(R.string.setup_back)) }
}

@Composable
private fun CreateFamilyContent(
    loading: Boolean,
    error: String?,
    onSubmit: (childName: String, parentName: String) -> Unit,
    onBack: () -> Unit,
) {
    var childName by remember { mutableStateOf("") }
    var parentName by remember { mutableStateOf("") }

    Text(stringResource(R.string.setup_create_family_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = childName,
        onValueChange = { childName = it },
        label = { Text(stringResource(R.string.setup_child_name_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = parentName,
        onValueChange = { parentName = it },
        label = { Text(stringResource(R.string.setup_parent_name_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(24.dp))
    if (loading) {
        CircularProgressIndicator()
    } else {
        Button(onClick = { onSubmit(childName, parentName) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.setup_create_button))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.setup_back)) }
    }
}

@Composable
private fun JoinFamilyContent(
    role: String,
    loading: Boolean,
    error: String?,
    onSubmit: (code: String, displayName: String?) -> Unit,
    onBack: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    val isChild = role == "child"

    Text(
        text = stringResource(if (isChild) R.string.setup_child_join_title else R.string.setup_join_family_title),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    if (!isChild) {
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text(stringResource(R.string.setup_parent_name_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
    }
    OutlinedTextField(
        value = code,
        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) code = it },
        label = { Text(stringResource(if (isChild) R.string.setup_child_code_hint else R.string.setup_code_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(24.dp))
    if (loading) {
        CircularProgressIndicator()
    } else {
        Button(
            onClick = { onSubmit(code, if (isChild) null else displayName) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.setup_join_button)) }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.setup_back)) }
    }
}

@Composable
private fun PairingCodeContent(code: String, onContinue: () -> Unit) {
    Text(
        text = stringResource(R.string.setup_pairing_code_title),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    Text(text = code.chunked(3).joinToString(" "), style = MaterialTheme.typography.displayMedium)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.setup_pairing_code_expires), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(32.dp))
    Button(onClick = onContinue) { Text(stringResource(R.string.setup_continue)) }
}
