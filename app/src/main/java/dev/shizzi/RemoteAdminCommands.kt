package dev.shizzi

import org.json.JSONArray
import org.json.JSONObject

fun processRemoteAdminCommand(
    command: LiveAdminCommand,
    store: CybercafeStore,
    nowMillis: Long,
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

            "module.messenger.set" -> outcome(
                store.setMessengerModuleEnabled(params.optBoolean("enabled", true)),
            )

            "module.media.set" -> {
                val enabled = params.optBoolean("enabled", true)
                val result = store.setMediaModuleEnabled(enabled)
                if (result.success) {
                    MediaPrefs.setEnabled(App.instance, enabled)
                    if (enabled) {
                        MediaServerService.start(App.instance)
                    } else {
                        MediaServerService.stop(App.instance)
                    }
                }
                outcome(result)
            }

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
