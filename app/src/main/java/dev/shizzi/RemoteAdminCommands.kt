package dev.shizzi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

fun processRemoteAdminCommand(
    command: LiveAdminCommand,
    store: CybercafeStore,
    nowMillis: Long,
    context: Context,
): AdminCommandResult {
    val params = runCatching { JSONObject(command.paramsJson) }.getOrElse { JSONObject() }

    fun outcome(result: RuleOutcome): AdminCommandResult =
        AdminCommandResult(command.id, result.success, result.message)

    return runCatching {
        when (command.action) {
            "account.create" -> outcome(
                store.createAccount(
                    params.optString("number"),
                    params.optString("pin"),
                    params.optString("name"),
                    nowMillis,
                ),
            )

            "account.rename" -> outcome(
                store.renameAccount(params.optString("number"), params.optString("name")),
            )

            "account.pin" -> outcome(
                store.resetPin(params.optString("number"), params.optString("pin")),
            )

            "account.enable" -> outcome(
                store.setAccountEnabled(
                    params.optString("number"),
                    params.optBoolean("enabled", true),
                ),
            )

            "account.delete" -> outcome(store.deleteAccount(params.optString("number")))

            "account.disconnect" -> {
                val number = params.optString("number")
                SessionService.disconnectAccount(number)
                AdminCommandResult(command.id, true, "Sessions du compte déconnectées.")
            }

            "session.disconnect" -> {
                val ip = params.optString("ip")
                if (ip.isBlank()) {
                    AdminCommandResult(command.id, false, "Adresse IP manquante.")
                } else {
                    SessionService.disconnectSession(ip)
                    AdminCommandResult(command.id, true, "Session $ip déconnectée.")
                }
            }

            "offer.upsert" -> {
                val kind = runCatching {
                    VoucherKind.valueOf(params.optString("kind", VoucherKind.DATA.name))
                }.getOrDefault(VoucherKind.DATA)
                outcome(
                    store.upsertOffer(
                        Offer(
                            id = params.optString("id"),
                            name = params.optString("name"),
                            kind = kind,
                            downloadBps = params.optLong("downloadBps"),
                            uploadBps = params.optLong("uploadBps"),
                            quotaBytes = params.optLong("quotaBytes"),
                            durationDays = params.optInt("durationDays"),
                            priceXpf = params.optInt("priceXpf"),
                        ),
                    ),
                )
            }

            "offer.delete" -> outcome(store.deleteOffer(params.optString("id")))

            "voucher.generate" -> {
                val generated = store.generateVouchers(
                    params.optString("offerId"),
                    params.optInt("count", 1).coerceIn(1, 100),
                    nowMillis,
                )
                if (generated.isEmpty()) {
                    AdminCommandResult(command.id, false, "Échec de génération.")
                } else {
                    AdminCommandResult(
                        command.id,
                        true,
                        generated.size.toString() + " voucher(s) généré(s).",
                        JSONArray(generated.map { it.code }).toString(),
                    )
                }
            }

            "voucher.enable" -> outcome(
                store.setVoucherEnabled(
                    params.optString("code"),
                    params.optBoolean("enabled", true),
                ),
            )

            "portal.set" -> outcome(
                store.setPortalCustomization(
                    params.optString("title"),
                    params.optString("message"),
                    params.optString("html"),
                ),
            )

            "admin.credentials" -> outcome(
                store.setRemoteAdmin(
                    true,
                    params.optString("username"),
                    params.optString("password"),
                ),
            )


            "media.offer.upsert" -> outcome(
                store.upsertMediaOffer(
                    MediaOffer(
                        id = params.optString("id"),
                        name = params.optString("name"),
                        durationMinutes = params.optLong("durationMinutes"),
                        priceXpf = params.optInt("priceXpf"),
                    ),
                ),
            )

            "media.offer.delete" -> outcome(
                store.deleteMediaOffer(params.optString("id")),
            )

            "media.voucher.generate" -> {
                val generated = store.generateMediaVouchers(
                    params.optString("offerId"),
                    params.optInt("count", 1).coerceIn(1, 100),
                    nowMillis,
                )
                if (generated.isEmpty()) {
                    AdminCommandResult(command.id, false, "Échec de génération Media.")
                } else {
                    AdminCommandResult(
                        command.id,
                        true,
                        generated.size.toString() + " voucher(s) Media généré(s).",
                        JSONArray(generated.map { it.code }).toString(),
                    )
                }
            }

            "media.voucher.enable" -> outcome(
                store.setMediaVoucherEnabled(
                    params.optString("code"),
                    params.optBoolean("enabled", true),
                ),
            )

            "media.enable" -> {
                val enabled = params.optBoolean("enabled", true)
                MediaPrefs.setEnabled(context, enabled)
                if (enabled) MediaServerService.start(context) else MediaServerService.stop(context)
                AdminCommandResult(
                    command.id,
                    true,
                    if (enabled) "Shizzi Media activé." else "Shizzi Media désactivé.",
                )
            }

            "media.folder.create" -> {
                val current = MediaFolderStore.load(context)
                if (current.size >= MediaFolderStore.MAX_FOLDERS) {
                    AdminCommandResult(command.id, false, "Maximum de dossiers Media atteint.")
                } else {
                    val kind = MediaKind.fromKey(params.optString("kind")) ?: MediaKind.FILMS
                    val created = MediaFolderStore.create(
                        params.optString("name").ifBlank { "Dossier Media" },
                        kind,
                    )
                    MediaFolderStore.upsert(context, created)
                    if (MediaPrefs.isEnabled(context)) MediaServerService.restart(context)
                    AdminCommandResult(
                        command.id,
                        true,
                        "Dossier Media créé.",
                        JSONObject().put("id", created.id).toString(),
                    )
                }
            }

            "media.folder.update" -> {
                val id = params.optString("id")
                val current = MediaFolderStore.byId(context, id)
                if (current == null) {
                    AdminCommandResult(command.id, false, "Dossier Media introuvable.")
                } else {
                    val accounts = if (params.has("allowedAccounts")) {
                        buildSet {
                            val array = params.optJSONArray("allowedAccounts") ?: JSONArray()
                            for (index in 0 until array.length()) {
                                normalizeMediaAccount(array.optString(index))
                                    .takeIf { it.isNotBlank() }
                                    ?.let(::add)
                            }
                        }
                    } else {
                        current.allowedAccounts
                    }
                    val kind = MediaKind.fromKey(params.optString("kind")) ?: current.kind
                    val updated = current.copy(
                        name = params.optString("name", current.name),
                        kind = kind,
                        enabled = params.optBoolean("enabled", current.enabled),
                        allowedAccounts = accounts,
                    )
                    val needsRescan = updated.kind != current.kind || updated.name != current.name
                    MediaFolderStore.upsert(context, updated)
                    if (MediaPrefs.isEnabled(context)) MediaServerService.restart(context)
                    if (needsRescan && updated.uri() != null) {
                        kotlin.concurrent.thread(name = "shizzi-media-admin-rescan") {
                            runCatching {
                                MediaIndex.rebuild(context.applicationContext)
                                if (MediaPrefs.isEnabled(context)) {
                                    MediaServerService.restart(context.applicationContext)
                                }
                            }
                        }
                    }
                    AdminCommandResult(command.id, true, "Dossier Media modifié.")
                }
            }

            "media.folder.delete" -> {
                val id = params.optString("id")
                val current = MediaFolderStore.byId(context, id)
                if (current == null) {
                    AdminCommandResult(command.id, false, "Dossier Media introuvable.")
                } else {
                    MediaFolderStore.remove(context, id)
                    MediaIndex.removeFolder(context.applicationContext, id)
                    if (MediaPrefs.isEnabled(context)) MediaServerService.restart(context)
                    AdminCommandResult(command.id, true, "Dossier Media supprimé.")
                }
            }


            "media.folder.source" -> {
                val id = params.optString("id")
                val current = MediaFolderStore.byId(context, id)
                val rawUri = params.optString("treeUri").trim()
                if (current == null) {
                    AdminCommandResult(command.id, false, "Dossier Media introuvable.")
                } else if (rawUri.isBlank()) {
                    MediaFolderStore.upsert(context, current.copy(treeUri = null))
                    MediaIndex.removeFolder(context.applicationContext, id)
                    if (MediaPrefs.isEnabled(context)) MediaServerService.restart(context)
                    AdminCommandResult(command.id, true, "Source Media retirée.")
                } else {
                    val uri = android.net.Uri.parse(rawUri)
                    if (!MediaRemoteSources.isAllowed(context, uri)) {
                        AdminCommandResult(
                            command.id,
                            false,
                            "Cette source n’a pas été autorisée par Android sur le routeur.",
                        )
                    } else {
                        MediaFolderStore.upsert(context, current.copy(treeUri = rawUri))
                        MediaIndex.removeFolder(context.applicationContext, id)
                        if (MediaPrefs.isEnabled(context)) MediaServerService.restart(context)
                        kotlin.concurrent.thread(name = "shizzi-media-admin-source-scan") {
                            runCatching {
                                MediaIndex.rebuild(context.applicationContext)
                                if (MediaPrefs.isEnabled(context)) {
                                    MediaServerService.restart(context.applicationContext)
                                }
                            }
                        }
                        AdminCommandResult(command.id, true, "Source Media appliquée. Scan lancé.")
                    }
                }
            }

            "media.scan" -> {
                kotlin.concurrent.thread(name = "shizzi-media-admin-scan") {
                    runCatching {
                        MediaIndex.rebuild(context.applicationContext)
                        if (MediaPrefs.isEnabled(context)) {
                            MediaServerService.restart(context.applicationContext)
                        }
                    }
                }
                AdminCommandResult(command.id, true, "Scan Media lancé.")
            }

            "media.browse" -> {
                val payload = MediaRemoteSources.browse(
                    context,
                    params.optString("parentUri").takeIf { it.isNotBlank() },
                )
                AdminCommandResult(
                    command.id,
                    true,
                    "Sources Media disponibles.",
                    payload.toString(),
                )
            }

            else -> AdminCommandResult(
                command.id,
                false,
                "Commande admin non autorisée : ${command.action}",
            )
        }
    }.getOrElse { failure ->
        AdminCommandResult(
            command.id,
            false,
            failure.message ?: failure.javaClass.simpleName,
        )
    }
}
