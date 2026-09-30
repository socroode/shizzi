package dev.shizzi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shizzi.ui.theme.ShizziTheme

class MediaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShizziTheme {
                MediaScreen(onBack = ::finish)
            }
        }
    }
}

@Composable
private fun MediaScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var pendingKind by remember { mutableStateOf<MediaKind?>(null) }
    var enabled by remember(revision) { mutableStateOf(MediaPrefs.isEnabled(context)) }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val kind = pendingKind
        pendingKind = null
        if (uri != null && kind != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            MediaPrefs.setTreeUri(context, kind, uri)
            revision++
            if (enabled) MediaServerService.restart(context)
        }
    }

    val urls = remember(revision, enabled) {
        if (enabled) MediaNetwork.portalUrls() else emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shizzi Media") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Retour") }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Portail multimédia local",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Les fichiers restent sur ce téléphone. La lecture par les appareils du hotspot reste locale et ne passe pas par Internet.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Serveur Media", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (enabled) "Actif sur le réseau local" else "Arrêté",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        enabled = checked
                        MediaPrefs.setEnabled(context, checked)
                        if (checked) MediaServerService.start(context) else MediaServerService.stop(context)
                        revision++
                    },
                )
            }

            HorizontalDivider()

            MediaKind.entries.forEach { kind ->
                val uri = MediaPrefs.treeUri(context, kind)
                FolderRow(
                    kind = kind,
                    uri = uri,
                    onChoose = {
                        pendingKind = kind
                        treeLauncher.launch(uri)
                    },
                    onClear = {
                        MediaPrefs.setTreeUri(context, kind, null)
                        revision++
                        if (enabled) MediaServerService.restart(context)
                    },
                )
            }

            HorizontalDivider()

            Text("Adresse du portail", fontWeight = FontWeight.SemiBold)
            if (!enabled) {
                Text("Active Shizzi Media pour afficher l’adresse locale.")
            } else if (urls.isEmpty()) {
                Text("Aucune adresse locale détectée. Active d’abord le hotspot Shizzi.")
            } else {
                urls.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Button(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("http://127.0.0.1:${MediaNetwork.PORT}/"),
                        )
                        context.startActivity(intent)
                    },
                ) {
                    Text("Ouvrir le portail sur ce téléphone")
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Test v0.4.3 : Films, Séries et Musique, lecture HTTP locale avec avance/retour. Les codecs réellement lisibles dépendent aussi du navigateur de l’appareil client.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun FolderRow(
    kind: MediaKind,
    uri: Uri?,
    onChoose: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(kind.label, fontWeight = FontWeight.SemiBold)
        Text(
            uri?.lastPathSegment?.substringAfterLast(':') ?: "Aucun dossier choisi",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onChoose) {
                Text(if (uri == null) "Choisir le dossier" else "Changer")
            }
            if (uri != null) {
                TextButton(onClick = onClear) {
                    Text("Retirer")
                }
            }
        }
    }
}
