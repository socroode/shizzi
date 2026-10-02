package dev.shizzi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import kotlin.concurrent.thread

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val mediaDiagnostics by SessionService.mediaDiagnostics.collectAsState()
    var revision by remember { mutableIntStateOf(0) }
    var pendingFolderId by remember { mutableStateOf<String?>(null) }
    var enabled by remember(revision) { mutableStateOf(MediaPrefs.isEnabled(context)) }
    val folders = remember(revision) { MediaFolderStore.load(context) }
    var scanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun startScan(kind: MediaKind? = null) {
        if (scanning) return
        scanning = true
        scanMessage = if (kind == null) {
            "Scan de la médiathèque en cours…"
        } else {
            "Scan ${kind.label} en cours…"
        }

        thread(name = "shizzi-media-index") {
            val result = runCatching {
                MediaIndex.rebuild(context.applicationContext, kind) { progress ->
                    mainHandler.post {
                        scanMessage =
                            "Scan ${progress.kind.label} : ${progress.filesFound} fichier(s), " +
                                "${progress.directoriesVisited} dossier(s)"
                    }
                }
            }
            mainHandler.post {
                scanning = false
                result.onSuccess { summary ->
                    scanMessage =
                        "Index Media : ${summary.total} fichier(s) " +
                            "(${summary.films} films, ${summary.series} séries, ${summary.music} musique)"
                    revision++
                    if (enabled) MediaServerService.restart(context)
                }.onFailure { failure ->
                    scanMessage = "Échec du scan Media : ${failure.message ?: failure.javaClass.simpleName}"
                }
            }
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val folderId = pendingFolderId
        pendingFolderId = null
        if (uri != null && folderId != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            MediaFolderStore.byId(context, folderId)?.let { folder ->
                MediaFolderStore.upsert(context, folder.copy(treeUri = uri.toString()))
            }
            revision++
            startScan(null)
        }
    }

    val urls = remember(revision, enabled) {
        if (enabled) listOf("http://192.0.2.1/media/") else emptyList()
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
                        if (enabled) "Actif derrière le portail compte Shizzi" else "Arrêté",
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

            Text(
                "Dossiers Media personnalisés (${folders.size}/${MediaFolderStore.MAX_FOLDERS})",
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Laisse « Comptes autorisés » vide pour rendre un dossier visible à tous les comptes. " +
                    "Sinon saisis les numéros de compte séparés par des virgules.",
                style = MaterialTheme.typography.bodySmall,
            )

            folders.forEach { folder ->
                CustomFolderRow(
                    folder = folder,
                    onSave = { updated ->
                        MediaFolderStore.upsert(context, updated)
                        revision++
                        startScan(null)
                    },
                    onChoose = {
                        pendingFolderId = folder.id
                        treeLauncher.launch(folder.uri())
                    },
                    onClear = {
                        MediaFolderStore.upsert(context, folder.copy(treeUri = null))
                        MediaIndex.removeFolder(context.applicationContext, folder.id)
                        scanMessage = "${folder.name} retiré de l’index Media."
                        revision++
                        if (enabled) MediaServerService.restart(context)
                    },
                    onDelete = {
                        MediaFolderStore.remove(context, folder.id)
                        MediaIndex.removeFolder(context.applicationContext, folder.id)
                        scanMessage = "${folder.name} supprimé de Shizzi Media."
                        revision++
                        if (enabled) MediaServerService.restart(context)
                    },
                )
                HorizontalDivider()
            }

            if (folders.size < MediaFolderStore.MAX_FOLDERS) {
                Button(
                    onClick = {
                        val created = MediaFolderStore.create("Dossier ${folders.size + 1}")
                        MediaFolderStore.upsert(context, created)
                        revision++
                    },
                ) {
                    Text("Ajouter un dossier")
                }
            }

            Button(
                enabled = !scanning && folders.any { it.uri() != null },
                onClick = { startScan(null) },
            ) {
                Text(if (scanning) "Scan en cours…" else "Scanner la médiathèque")
            }

            val summary = remember(revision) { MediaIndex.summary(context.applicationContext) }
            Text(
                scanMessage ?: "Index : ${summary.total} fichier(s) — " +
                    "${summary.films} films, ${summary.series} séries, ${summary.music} musique",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()

            Text("Adresse Media sécurisée", fontWeight = FontWeight.SemiBold)
            if (!enabled) {
                Text("Active Shizzi Media pour afficher l’adresse locale.")
            } else if (urls.isEmpty()) {
                Text("Aucune adresse locale détectée. Active d’abord le hotspot Shizzi.")
            } else {
                urls.forEach { Text("$it — compte Shizzi requis", style = MaterialTheme.typography.bodyMedium) }
                Button(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("http://${MediaNetwork.LOOPBACK_HOST}:${MediaNetwork.PORT}/"),
                        )
                        context.startActivity(intent)
                    },
                ) {
                    Text("Ouvrir le portail sur ce téléphone")
                }
            }

            HorizontalDivider()

            MediaDiagnosticsPanel(
                events = mediaDiagnostics,
                onCopy = { text ->
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostic Media Shizzi", text))
                },
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "Media indexé : scanne la médiathèque après avoir choisi ou modifié un dossier. La lecture reste locale avec avance/retour.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CustomFolderRow(
    folder: MediaFolderConfig,
    onSave: (MediaFolderConfig) -> Unit,
    onChoose: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(folder.id, folder.name) { mutableStateOf(folder.name) }
    var accounts by remember(folder.id, folder.allowedAccounts) {
        mutableStateOf(folder.allowedAccounts.joinToString(", "))
    }
    var kind by remember(folder.id, folder.kind) { mutableStateOf(folder.kind) }
    var enabled by remember(folder.id, folder.enabled) { mutableStateOf(folder.enabled) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Nom du dossier") },
            singleLine = true,
        )
        OutlinedTextField(
            value = accounts,
            onValueChange = { accounts = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Comptes autorisés (vide = tous)") },
            singleLine = true,
        )
        Text(
            folder.uri()?.lastPathSegment?.substringAfterLast(':') ?: "Aucun dossier Android choisi",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Actif")
            Switch(
                checked = enabled,
                onCheckedChange = { enabled = it },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    kind = when (kind) {
                        MediaKind.FILMS -> MediaKind.SERIES
                        MediaKind.SERIES -> MediaKind.MUSIC
                        MediaKind.MUSIC -> MediaKind.FILMS
                    }
                },
            ) {
                Text("Type : ${kind.label}")
            }
            Button(onClick = onChoose) {
                Text(if (folder.uri() == null) "Choisir" else "Changer")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val allowed = accounts
                        .split(',', ';', '\n')
                        .map(::normalizeMediaAccount)
                        .filter { it.isNotBlank() }
                        .toSet()
                    onSave(
                        folder.copy(
                            name = name.trim().ifBlank { "Dossier Media" },
                            kind = kind,
                            enabled = enabled,
                            allowedAccounts = allowed,
                        ),
                    )
                },
            ) {
                Text("Enregistrer")
            }
            if (folder.uri() != null) {
                TextButton(onClick = onClear) { Text("Retirer source") }
            }
            TextButton(onClick = onDelete) { Text("Supprimer") }
        }
    }
}

@Composable
private fun MediaDiagnosticsPanel(
    events: List<LiveMediaDiagnostic>,
    onCopy: (String) -> Unit,
) {
    Text("Diagnostic Media", fontWeight = FontWeight.SemiBold)

    if (events.isEmpty()) {
        Text(
            "Aucun accès Media enregistré pour cette session. " +
                "Depuis un appareil client connecté à un compte Shizzi, ouvre Media : " +
                "le diagnostic apparaîtra ici automatiquement.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    val latest = events.last()
    Text(
        mediaDiagnosticInterpretation(latest),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        formatMediaDiagnostic(latest),
        style = MaterialTheme.typography.bodySmall,
    )

    val recent = events.takeLast(10)
    Button(onClick = { onCopy(recent.joinToString("\n\n") { formatMediaDiagnostic(it) }) }) {
        Text("Copier le diagnostic")
    }

    if (recent.size > 1) {
        Text(
            "Historique récent : ${recent.size} accès Media. " +
                "L'écran se met à jour automatiquement pendant que Shizzi est actif.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun mediaDiagnosticInterpretation(event: LiveMediaDiagnostic): String = when (event.result) {
    "proxied" ->
        "✓ Shizzi reconnaît le compte et transmet bien Media au serveur local."
    "account_required" ->
        "Compte non reconnu pour cet appareil : le blocage se produit avant le serveur Media."
    "backend_unavailable" ->
        "Le compte est reconnu, mais le serveur Media local ne répond pas sur ${event.backend.ifBlank { "127.0.0.1:8088" }}."
    "proxy_copy_error" ->
        "Le serveur Media a été joint, mais le transfert vers l'appareil client s'est interrompu."
    else ->
        "Événement Media détecté : ${event.result.ifBlank { "résultat inconnu" }}."
}

private fun formatMediaDiagnostic(event: LiveMediaDiagnostic): String = buildString {
    append("Client : ").append(event.clientIp.ifBlank { "—" })
    append("\nCompte : ").append(event.accountNumber.ifBlank { "—" })
    append("\nAuthentifié : ").append(if (event.accountAuthenticated) "OUI" else "NON")
    append("\nChemin : ").append(event.path.ifBlank { "—" })
    append("\nProxy : ").append(event.proxyTarget.ifBlank { "—" })
    append("\nBackend : ").append(event.backend.ifBlank { "—" })
    append("\nBackend connecté : ").append(if (event.backendConnected) "OUI" else "NON")
    append("\nRésultat : ").append(event.result.ifBlank { "—" })
    if (event.bytesCopied > 0L) append("\nOctets transférés : ").append(event.bytesCopied)
    if (event.error.isNotBlank()) append("\nErreur : ").append(event.error)
}
