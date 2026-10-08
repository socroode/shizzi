package dev.shizzi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun RouterActivationScreen(onActivate: (String) -> RouterActivationResult) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Activer Shizzi Routeur", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("Saisis le PIN d'activation du routeur. Cette opération est nécessaire une seule fois, même sans connexion Internet.")
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { next ->
                pin = next.filter { it in '0'..'9' }.take(6)
                message = null
            },
            label = { Text("PIN à 6 chiffres") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(message.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                message = when (onActivate(pin)) {
                    RouterActivationResult.ACTIVATED,
                    RouterActivationResult.ALREADY_ACTIVATED -> null
                    RouterActivationResult.WRONG_PIN -> "PIN incorrect."
                    RouterActivationResult.TOO_MANY_ATTEMPTS ->
                        "Trop d'essais. Réessaie dans une minute."
                    RouterActivationResult.STORAGE_ERROR ->
                        "L'activation n'a pas pu être enregistrée sur cet appareil."
                }
                pin = ""
            },
            enabled = pin.length == 6,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Activer Shizzi")
        }
    }
}
