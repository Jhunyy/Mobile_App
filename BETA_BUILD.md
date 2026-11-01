# Supervised ApCiDa beta build

The `beta` Android build type is for reviewing the normal app's invite setup and consent screens on a phone. It uses the package `com.apcida.smishingdetector.beta` and the label **ApCiDa Beta**, so it can be installed beside the debug app. It uses the release network policy (HTTPS only) and has no debug synthetic-test launcher activities.

The beta APK is signed with the Android **debug key** so a tester can install it. It is not a production-signed release. Builds made on different computers normally have different debug keys and cannot update one another in place. Do not distribute this as a production APK.

Automatic remote analysis of received SMS remains disabled by `RemoteTransportGate.PRODUCTION_READY=false`. Invite setup stores a unique per-phone backend credential and Cloudflare Access credential but does not grant SMS consent or open the upload gate. The normal app should continue to show remote AI as unavailable in this build. Use only invented text in any separate synthetic testing screen.

Before building, put the public host in your ignored `local.properties` file:

```properties
apcida.productionApprovedHost=sms.apcida.stream
```

Build the beta APK with `./gradlew :app:assembleBeta` on macOS or `./gradlew.bat :app:assembleBeta` on Windows. The APK is `app/build/outputs/apk/beta/app-beta.apk`. Install it with `adb install -r` followed by that file path. Keep one builder's APK signing key for subsequent updates; if you switch computers, uninstalling the old beta loses its local data and credentials, so revoke that phone's service access first.

Phone review: open **ApCiDa Beta**, complete local onboarding, and check that Settings shows **Set up invite-only service** while AI Protection says remote analysis is unavailable. Do not redeem an invite until the owner schedules a supervised beta check. This build does not yet prove signed production release setup or automatic incoming-SMS analysis.
