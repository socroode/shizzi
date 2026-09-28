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
- an account is never bound to an IP or MAC: devices are live portal sessions only;
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

## Layer 2 — portal, sessions and datapath sync

Chain: physical device → identification (pre-NAT tethering state) → portal session
→ account → Data/Unlimited → limiter + shared quota → Internet.

### Datapath (Go)

- The datapath starts **fail-closed** (portal + attribution required) until Android
  pushes its first policy.
- **TCP**: the client handshake is completed first, then the flow is attributed.
  Android publishes the tethering NAT rule once the flow is established, so holding
  the SYN until the rule appears could not work.
- **UDP**: gVisor runs the UDP forwarder handler inside packet dispatch. It now only
  creates the endpoint; attribution runs in a goroutine. A tuple that just failed is
  not waited on again for 5 s.
- **DNS** (53/udp+tcp) is always carried, before login and even unattributed, and is
  never billed (`unattributedDnsBytes`).
- **Attribution never guesses**: exact rule, or same translated port + destination
  with a different protocol label. Port-only matches are refused and counted as
  `looseCandidateFlows`. The single-client fallback requires Android's own
  connected-client list with exactly one client.
- **Sessions** are keyed by the resolved client IP. Released on `/logout`, on admin
  "Déconnecter", or when Android stops listing the client (45 s grace), so a reused
  DHCP address never inherits a login.
- **Shared quota**: `accountUsage[account]` counts up/down/Data bytes across all
  sessions. The live balance is `dataBalanceBytes - (dataBytes - consumedMarkerBytes)`.

### Android sync loop (`SessionService.followCybercafe`)

A single sequential loop, every second:

1. read counters, sessions and voucher claims;
2. apply usage deltas to accounts once (`UsageLedger`);
3. redeem claims and remember the results for the portal;
4. push the policy **only** when `CybercafeStore.policyRevision` changed (admin edit,
   recharge…), the datapath epoch changed, claim results are pending, or every 60 s.

Consumption does not bump the revision, so the datapath is not reconfigured on every
tick. Balance and markers are always pushed from the same loop, so they describe the
same instant. Consumption is written to disk at most every 15 s (forced on stop).

### Diagnostics on the Reno11 (Connected Devices tab)

- *Flux refusés* growing while a phone is logged in → its flows are not found in
  `dumpsys tethering`.
- *Refus avec port traduit connu* > 0 → the OEM dump prints destinations
  differently; adapt the parser, do not loosen matching.
- *Clients listés par Android* = 0 → the connected-client list is not parsed on this
  build; departed-client release and the single-client fallback are then inactive.
