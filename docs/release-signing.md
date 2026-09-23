# Permanent APK signing

Shizzi release APKs must always use the same signing key. Android only permits an
installed app to be updated by another APK signed by the same key.

The repository never stores the private key. GitHub Actions reads these
repository secrets:

- `SHIZZI_KEYSTORE_B64` — base64 of the complete JKS/keystore file.
- `SHIZZI_STORE_PASSWORD` — keystore password.
- `SHIZZI_KEY_ALIAS` — signing-key alias.
- `SHIZZI_KEY_PASSWORD` — private-key password.

Once these four secrets exist, the Build workflow still produces the normal
debug APK for CI and additionally builds the signed release APK artifact.

Back up the JKS and its passwords somewhere durable. Losing this signing key
means existing installations signed with it cannot be updated in place.

Do not commit the JKS, `keystore.properties`, passwords, or base64 key data.


## Pinned signing certificate

The permanent Shizzi signing certificate is pinned to this SHA-256 fingerprint:

`1F:6D:2E:EF:58:BF:9C:40:F0:9B:51:55:C9:CA:5F:CC:D4:AD:1F:C8:44:73:E9:B0:66:CC:FD:4F:9B:40:30:6B`

The CI workflow checks this fingerprint before producing a signed release. This
prevents an accidental replacement key from silently creating APKs that cannot
update existing installations.
