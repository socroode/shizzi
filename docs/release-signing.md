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
