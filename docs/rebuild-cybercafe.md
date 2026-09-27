# Shizzi clean rebuild — cybercafé core

This branch starts from the original upstream fork baseline (`4f8f049`).

The rebuild is intentionally layered. No release is produced from this branch without
explicit approval.

## Layer 1 — prepaid domain

The first layer owns durable commercial state independently from the TUN:

- accounts with salted SHA-256 PIN hashes;
- Data and Unlimited offers;
- voucher inventory and single redemption;
- Data rule: bytes accumulate and validity resets from the latest recharge;
- Unlimited rule: duration accumulates;
- Data remains untouched while Unlimited is active;
- one active device binding per account for the first stable rebuild;
- upload/download accounting per account;
- schema-versioned JSON persistence.

Default offers follow the reconstruction specification:

| Offer | Speed | Data | Validity | Price |
| --- | --- | --- | --- | --- |
| Eco 12 Go | 2/1 Mbps | 12 Go | 30 days | 1,000 XPF |
| Eco+ 20 Go | 4/2 Mbps | 20 Go | 30 days | 1,500 XPF |
| Data 100 Go | 10/5 Mbps | 100 Go | 30 days | 5,000 XPF |
| Illimité Eco | 1/1 Mbps | Unlimited | 30 days | 4,000 XPF |
| Illimité Confort | 4/2 Mbps | Unlimited | 30 days | 6,000 XPF |

Later layers connect this state to Account Editor, Voucher Editor, Connected Devices,
the captive portal, per-device traffic policy and Shizzi Conso.
