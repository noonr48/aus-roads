# Release Signing

## Setup

1. Generate a keystore:
```bash
keytool -genkey -v -keystore aus-roads-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias aus-roads
```

2. Copy `signing.properties.example` to `signing.properties`:
```bash
cp android/signing.properties.example android/signing.properties
```

3. Edit `signing.properties` with your keystore path, password, and alias.

4. Build signed release:
```bash
cd android && ./gradlew assembleWithNetworkRelease
```

## Play Store upload

1. Create a Google Play Developer account ($25 one-time fee)
2. Create a new app in the Play Console
3. Upload the `app-withNetwork-release.apk` from `app/build/outputs/apk/withNetwork/release/`
4. Fill in the store listing (see play-store-listing.md)
5. Complete the content rating questionnaire
6. Set up pricing (Free)
7. Submit for review
