# Production signing preparation

The `release` variant signs only when all four `APCIDA_RELEASE_*` environment values are set: `KEYSTORE_FILE`, `STORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. The signed-release script refuses to run without them and verifies the output signature. No password or keystore belongs in Git, `local.properties`, or the APK.

Use one stable signing key for updates. Keep its keystore and passwords in an encrypted off-device recovery backup before distributing the first release. A locally signed APK cannot be updated with a different certificate. The existing `beta` APK is signed with a debug key and uses a different package name, so it is not the production release.

The signing workflow passed with a disposable test key; that key and APK were removed. A stable production signing key has not yet been created or distributed. Create it after an off-device recovery destination is ready, then include the keystore in the encrypted recovery bundle and keep its passwords in a separate password manager.

After the owner has backed up the key and confirmed the distribution channel, run `scripts/build-signed-release.sh` with the four values supplied privately in the environment. The script writes `app/build/outputs/apk/release/app-release.apk` and checks its Android signature. A successful build does not open remote SMS analysis: `RemoteTransportGate.PRODUCTION_READY` remains `false` until a separate activation review.
