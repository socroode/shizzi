package datapath

type sessionEndEvent struct {
	AtMillis      int64  `json:"atMillis"`
	IP            string `json:"ip"`
	AccountNumber string `json:"accountNumber"`
	Reason        string `json:"reason"`
	Message       string `json:"message"`
}

func sessionEndMessage(reason string) string {
	switch reason {
	case "confirmed-identity-replacement":
		return "L'identité de l'appareil à cette adresse a changé."
	case "confirmed-departure":
		return "Départ du Wi-Fi confirmé."
	case "account-moved-to-another-device":
		return "Compte utilisé sur un autre appareil."
	case "account-or-credentials-changed":
		return "Compte ou identifiants modifiés."
	case "account-disabled-or-deleted":
		return "Compte suspendu ou supprimé."
	case "account-or-association-changed":
		return "Compte ou association de l'appareil modifié."
	case "user-logout":
		return "Déconnexion demandée par l'utilisateur."
	case "admin-disconnect":
		return "Déconnexion demandée par l'administrateur."
	case "service-stopped":
		return "Arrêt du service Shizzi."
	default:
		return "Session fermée."
	}
}

// A cached MAC remains useful for display, never for an identity decision.
func sessionDisplayMAC(confirmed, cached string) string {
	if confirmed != "" {
		return confirmed
	}
	return cached
}
