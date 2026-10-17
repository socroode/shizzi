# Shizzi 0.4.3.5.8 candidate

Video continuity applies to all TCP/UDP applications, including live streams,
calls and IP cameras. Existing captive portal, account portability, one-device
sessions, vouchers, Confort 4/2 and aggregate router 40/4 ceilings are preserved.

Waiting bandwidth transfers now own cancellable queue tickets rather than
future time reservations. Account and router admission is committed together.
An account enters the shared router FIFO only when its own next packet is
ready, avoiding a slow account blocking unrelated accounts. The 250 ms burst
window remains; a larger individual datagram can use one packet of credit.

A normal TCP EOF propagates CloseWrite and lets the opposite direction drain.
Errors, logout, revocation and service shutdown still close both directions.
The bidirectional UDP activity tracking from 0.4.3.5.6 remains.

Diagnostics distinguish flow direction endings from account session endings,
record bounded recent end reasons and aggregate pacing waits/cancellations.

Tests cover cancellation with surviving/new transfers, concurrent aggregate
ceilings, both TCP FIN directions, production gVisor forwarding with exact
simulated NAT, revocation after FIN, and shaped bidirectional TCP+UDP for
1/2/6/8 accounts. The 8-account case lasts at least 70 seconds.

These are Go socket/netstack simulations with mocked Android presence/NAT,
plus Android JVM unit tests. They do not emulate Android phones or radios and
do not prove real-world application stability. An on-device prolonged video
trial is still needed after installation. No release is published.

Additional regressions cover cancellation in the router queue, preservation of spent bandwidth credit, and a one-way shaped UDP downlink exceeding the 60-second idle timeout. The gVisor fixture uses a local unicast host address and a bounded connection context; production loopback/martian checks remain enabled.
