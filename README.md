# pCloud TV

An Android TV app for streaming what is already in your pCloud: videos, photos and
music. Browse, play, look, listen — nothing else. No upload, no delete, no sync.

Built against the HTTP/JSON API documented at <https://docs.pcloud.com/>. The subset
this app uses is written up in [docs/PCLOUD_API.md](docs/PCLOUD_API.md), including
the parameter and response details each screen depends on.

## What it does

| | |
|---|---|
| **Browse** | `listfolder` from the root (`folderid 0`) down, folders first, with a breadcrumb trail. Thumbnails come from the batch `getthumbslinks` call. |
| **Video** | Streams the original file via `getfilelink` — full quality, no transcode, no resolution cap. If the TV cannot decode it, falls back to `gethlslink` adaptive HLS. |
| **Photos** | Full resolution via `getfilelink`, left/right through the folder, neighbours prefetched. |
| **Music** | `getaudiolink` (mp3), so FLAC/ALAC/Opus play on boxes with a narrow decoder set. Now-playing screen with album art, ID3 tags, and a queue from the current folder. |

## Signing in

Two routes, both on the login screen:

- **Email and password.** Sent as a digest (`getdigest` + `passworddigest`), so the
  password itself never goes over the wire. If the account has 2FA, a field for the
  6-digit code appears once pCloud asks for it.
- **API auth token**, pasted from pCloud on a computer. Bypasses 2FA entirely and is
  much easier to enter once with a remote.

pCloud runs two data centers and an account lives in exactly one. The region picker
is a hint — if it is wrong, login retries the other one and then remembers the right
host.

## Remote control

| Screen | Keys |
|---|---|
| Browser | D-pad to move, OK to open, Back to go up a folder |
| Video | Standard player controls; Back to exit |
| Photos | ◀ ▶ previous/next, Back to exit |
| Music | ◀ ▶ seek 10s, ▲ ▼ change track, OK play/pause, Back to exit |

## Build

```
./gradlew :app:assembleDebug
./gradlew :app:test            # unit tests for the data layer
./gradlew pcloudApk            # signed release build -> ./Pcloud.apk
```

Requires the Android SDK (compileSdk 36) and JDK 17. `minSdk` is 26, which covers
every Android TV device still receiving updates.

### Release signing

`pcloudApk` signs with the keystore named in `app/keystore.properties`:

```
storeFile=pcloud-release.jks
storePassword=…
keyAlias=pcloud
keyPassword=…
```

Neither that file nor the `.jks` is in version control. Generate one with:

```
keytool -genkeypair -v -keystore app/pcloud-release.jks -alias pcloud \
  -keyalg RSA -keysize 2048 -validity 10000
```

Keep a backup of the keystore and its passwords somewhere other than this machine —
Android will only accept an update to an installed app if it is signed with the same
key, so losing it means every user has to uninstall and reinstall.

Without `app/keystore.properties` the release build falls back to the debug key, so
a fresh clone still builds; the resulting APK just is not distributable.

## Layout

```
data/
  api/         Retrofit service, OkHttp client, typed pCloud errors
  model/       metadata + response types, file-type classification
  repository/  auth, folder listing, link resolution, link cache, digest
ui/            Compose screens: login, browser, video, audio, photos
docs/          scraped API reference
```

## Notes on the implementation

- **File typing uses the API's `category` field** (0 uncategorized, 1 image,
  2 video, 3 audio, 4 document, 5 archive), falling back to the server's `icon`
  hint, then MIME, then extension. Guessing from the extension alone misfiles any
  video served as `application/octet-stream`.
- **Media links expire.** Every link response carries an `expires` timestamp;
  `LinkCache` parses it and re-requests before the deadline, so playback never
  starts on a dead URL.
- **Multiple storage hosts.** Link responses list several hosts, best first. The
  video player walks the rest of the list on a host-level failure before concluding
  the file is unplayable.
- **Debug HTTP logging is redacted.** pCloud passes credentials as query
  parameters, so the request line alone would leak the auth token into logcat.
