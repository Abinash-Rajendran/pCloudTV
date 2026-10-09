# pCloud API reference (scraped from https://docs.pcloud.com/)

Only the subset this TV app needs: auth, folder listing, streaming links, thumbnails.
Scraped 2026-10-06. Quoted text is from the docs pages.

---

## 1. Hosts and regions

pCloud runs two independent data centers. **An account lives in exactly one of them**
and its auth token is only valid on that host.

| locationid | Host             | Region        |
|-----------:|------------------|---------------|
| 1          | `api.pcloud.com` | United States |
| 2          | `eapi.pcloud.com`| Europe        |

Requests to the wrong host fail with `2011` (user is in EU) or `2012` (user is in US).
The app stores the resolved host with the token and builds every later call from it.

---

## 2. Authentication

There is **no `login` endpoint**. Credentials are global parameters, so "every method
could be used as login point". The docs recommend `userinfo`, which also returns the
user record on success.

### Login global parameters

| Param                 | Meaning |
|-----------------------|---------|
| `getauth`             | set to any value to have a token returned |
| `authexpire`          | seconds from now until `expire` |
| `authinactiveexpire`  | seconds from now for idle expiry |
| `device`              | identifies the device; falls back to `User-Agent` |
| `logout`              | if set, logs out the current connection |

### 2a. Plain password — `GET /userinfo`

```
userinfo?getauth=1&username=<email>&password=<pass>
```

"This method should only be used over SSL connections." We are always on HTTPS, but
digest is still tried first because it never puts the password on the wire.

### 2b. Digest — `GET /getdigest` then `GET /userinfo`

```
getdigest                       -> { result: 0, digest: "<digest>", expires: ... }
userinfo?getauth=1&username=<email>&digest=<digest>&passworddigest=<pd>
```

```
passworddigest = sha1( password + sha1( lowercase(username) ) + digest )
```

Every sha1 is rendered as **40 hex chars, lowercase**.

> Implementation note: this is the one place the old code was wrong. It computed
> `sha1( sha1(password + userhash) + digest )` — an extra inner hash — so digest
> login always failed and the app silently fell back to plain-password login.
> See `PCloudRepository.calculatePasswordDigest`.

### 2c. Token

```
{METHOD}?auth={TOKEN}&{PARAMS}
```

Tokens are "at most 64 bytes long". `expire` is a hard deadline; `expire_inactive`
"increments every time the token is used on the server". `listtokens` reports both.

### 2d. OAuth 2.0 (not used by this app — no client_id registered)

Authorize page: `https://my.pcloud.com/oauth2/authorize`
Required `client_id`, `response_type` (`code` | `token`); optional `redirect_uri`,
`state`, `force_reapprove`, `permissions` (only `manageshares` documented).

Token flow redirect (fragment, read client-side):
```
redirect_uri#access_token=X&token_type=bearer&uid=N&state=S&locationid=[1|2]&hostname=[api|eapi].pcloud.com
```
Code flow redirect (query): `?code=X&state=S&locationid=..&hostname=..`
"Consequent API calls have to be made to that host name."

### 2e. Logout — `GET /logout?auth=<token>`

Invalidates the token server-side. Call it, then clear local state.

---

## 3. Folder listing — `GET /listfolder`

Auth: **yes**. Root folder is always `folderid = 0`.

| Param         | Type   | Notes |
|---------------|--------|-------|
| `folderid`    | int    | use this |
| `path`        | string | "discouraged" |
| `recursive`   | int    | full tree; every dir then carries `contents` |
| `showdeleted` | int    | include undeletable items |
| `nofiles`     | int    | folder structure only |
| `noshares`    | int    | hide items shared in by others |

Returns `{ result, metadata }` where `metadata.contents` is an array of metadata
objects. "Recursively listing the root folder is not an expensive operation."

Errors: `1000` login required · `1002` no path/folderid · `2000` login failed ·
`2003` access denied · `2005` directory does not exist · `4000` too many login
tries from this IP · `5000` internal error.

---

## 4. Metadata structure

`https://docs.pcloud.com/structures/metadata.html`

### Common

| Field | Type | Meaning |
|---|---|---|
| `id` | string | `d<folderid>` for folders, `f<fileid>` for files |
| `folderid` | int | folders only |
| `fileid` | int | files only |
| `parentfolderid` | int | containing folder |
| `name` | string | |
| `isfolder` | bool | |
| `ismine` | bool | if false, adds `canread`, `canmodify`, `candelete`, `cancreate` (folders only) and owner `userid` |
| `isshared` | bool | |
| `created` / `modified` | timestamp | |
| `icon` | string | document, database, archive, web, gis, spreadsheet, font, presentation, image, diskimage, package, executable, audio, video, file |
| `category` | int | see below |
| `thumb` | bool | **"true if thumbs can be created from the object"** |
| `size` | int | bytes; files only |
| `contenttype` | string | files only |
| `hash` | int | 64-bit content hash — changes when the file changes, so it is the cache key for thumbnails |
| `contents` | array | folder children |
| `isdeleted` | bool | only ever present (and true) on deleted objects |
| `path` | string | sometimes; "Recursive listings do not have path provided." |

### `category` values — **the authoritative way to type a file**

| Value | Category |
|------:|-------------|
| 0 | uncategorized |
| 1 | image |
| 2 | video |
| 3 | audio |
| 4 | document |
| 5 | archive |

> Implementation note: the old code ignored `category` and guessed from MIME +
> file extension, so an `.mkv` with `contenttype: application/octet-stream` and an
> extension outside its hardcoded list was typed `OTHER` and refused to play.
> `PCloudFileType.from()` now reads `category` first and only guesses as a fallback.

### Image extras
`width`, `height` (int, px).

### Audio extras
`artist`, `album`, `title`, `genre`, `trackno` — all strings. Used for the
now-playing screen.

### Video extras
`width`, `height` (px) · `duration` (float seconds, **sent as a string**) ·
`fps` (float as string) · `videocodec`, `audiocodec` (string) ·
`videobitrate`, `audiobitrate` (int kbit) · `audiosamplerate` (int Hz) ·
`rotate` (0 | 90 | 180 | 270).

---

## 5. Streaming links

**All four of these "can't be used from web applications. Referrer is restricted to
pcloud.com."** A native Android client is fine — this restriction is about browser
referrers, which is also why the app must not route these through a WebView.

### Common response shape and URL construction

```json
{ "result": 0,
  "expires": "Thu, 03 Oct 2013 01:17:11 +0000",
  "path": "/hash/My video.mp4",
  "hosts": ["c11.pcloud.com", "c20.pcloud.com"] }
```

> "You need to construct the URL yourself by concatenating http:// or https://
> with one of the hosts (first one) and the path."

```
https:// + hosts[0] + path
```

"The first server is the one we consider best for current download." Later entries
are fallbacks. `path` arrives **already percent-encoded** (`/hash/My%20thumb.jpg`),
so it must be concatenated raw — re-encoding it corrupts the link.

> Implementation note: the old `downloadUrl` getter did exactly the right
> concatenation, but nothing honoured `expires`. Links are short-lived, so a token
> held across a long browse session produced a dead URL. `LinkCache` now stores
> `expires` and re-requests when stale.

### `GET /getfilelink` — original bytes, no transcode

`fileid` | `path` required. Optional: `forcedownload` (serve as
`application/octet-stream`), `contenttype`, `maxspeed` (bytes/sec), `skipfilename`.
Content type is otherwise derived from the extension.

This is the **primary video path** in this app: full original quality, no server-side
transcode and no resolution cap. Also used for full-size photos.

Errors: `1000` · `1004` no fileid/path · `1005` unknown content-type · `2000` ·
`2002` · `2003` · `2009` file not found · `2010` invalid path · `2011` requested
speed limit too low · `4000` · `5002` no servers available.

### `GET /gethlslink` — adaptive HLS, the fallback

`fileid` | `path` required. Optional: `abitrate` (16–320 kbit),
`vbitrate` (16–4000 kbit), `resolution` (`WIDTHxHEIGHT`, 64x64 – **1280x960**),
`skipfilename`.

Returns **JSON, not a redirect** — `path` ends in `.m3u8`. Build the URL the same way
and hand it to an HLS-capable player.

Used when `getfilelink` playback fails (codec the TV can't decode, or a container
ExoPlayer won't extract). Server-side transcode, so the 1280x960 cap applies —
that is the explicit trade for always-playable.

Errors: same set as above plus `2044` "Video links can only be generated for videos."

### `GET /getvideolinks` — list of pre-made variants

`fileid` | `path` required. Optional: `forcedownload`, `contenttype`, `maxspeed`,
`skipfilename`.

Returns `{ result, variants: [...] }`. Each variant: `path`, `hosts`, `width`,
`height`, `duration`, `fps`, `videobitrate`, `audiobitrate`, `videocodec`,
`audiocodec`, `isoriginal` (bool — true on the untranscoded source), `expires`;
the original also carries `rotate` and `audiosamplerate`.

Not used. Documented here because it is the endpoint a future quality picker would
need: it is the only one that reports what renditions actually exist, with real
codec and bitrate figures, rather than asking the server to make one.

### `GET /getvideolink` — single transcoded stream

Optional: `forcedownload`, `contenttype`, `maxspeed`, `skipfilename`,
`abitrate` (16–320), `vbitrate` (16–4000), `resolution` (64x64 – 1280x960),
`fixedbitrate` (bool, disables adaptive).

Transcodes to **FLV / x264 / mp3**, default 1000 kbit video (adaptive) + 128 kbit
audio. The generated link accepts `?start=<seconds>` to skip ahead.

Not used — FLV is a worse target for ExoPlayer than either original or HLS.

### `GET /getaudiolink` — mp3 stream

`fileid` | `path` required. Optional: `forcedownload`, `contenttype`,
`abitrate` (16–320 kbit). Default **192 kbit**.

Transcodes to mp3 and can extract the audio track out of a video file. The docs
suggest it as the way "to play FLAC and other new formats on devices that only
support mp3 playback". The link accepts `?start=<seconds>`.

This is the music path: FLAC/ALAC/OGG on a TV box with a narrow decoder set all
arrive as mp3.

---

## 6. Thumbnails

### `GET /getthumblink`

| Param | Required | Notes |
|---|---|---|
| `fileid` / `path` | one | |
| `size` | yes | `WIDTHxHEIGHT` |
| `crop` | no | exact requested size, trimmed symmetrically first |
| `type` | no | `png`; default JPEG |

**Size rules:** width 16–2048, height 16–1024, and each **divisible by 4 or 5**.
Without `crop` the aspect ratio is kept, so "one dimension might be less than
requested". With `crop` a 1024x768 source at 128x128 is "first converted to 768x768
by cutting 128 columns from both sides and then resized to 128x128".

Response is the `getfilelink` shape plus `size` — the *actual* output size, which
"will match reqested size if crop is specified or may differ otherwise".

Only files whose metadata has **`thumb == true`** can produce a thumbnail.

Thumbs are "cached for unspecified amount of time (or until file) changes". The docs
advise caching locally and watching the file's `hash` — if the hash changes, refetch.

Errors: `1000` · `1004` · `1014` thumb cannot be made from this file type ·
`1015` invalid thumb size · `2000` · `2002` · `2003` · `2009` · `2010` ·
`3001` could not create thumb · `4000` · `5002`.

### `GET /getthumbslinks` — batch, strongly preferred for a grid

`fileids` (comma-separated) + `size` required; `crop`, `type` optional and
**applied uniformly to every file** — per-file sizes are not possible.

> "preferable than multiple calls to getthumblink (even if pipelined)" because it
> "connects to multiple storage serves simultaneously" and is only marginally
> slower than a single-thumb request.

Returns `{ result, thumbs: [...] }`. Each entry has `result` and `fileid`; on
success also `path`, `hosts`, `expires`, `size`; on failure an `error` string.
**Per-entry results must be checked individually** — the top-level `result` can be 0
while an individual file failed with `1014`.

No maximum fileid count is documented. The app batches 60 per call anyway.

Errors: `1000` · `1015` invalid thumb size · `1038` please provide 'fileids' ·
`2000` · `4000`.

---

## 7. Error codes used by this app

| Code | Meaning |
|-----:|---------|
| 1000 | log in required |
| 1002 | no path or folderid provided |
| 1004 | no fileid or path provided |
| 1005 | unknown content-type requested |
| 1014 | thumb cannot be created from this file type |
| 1015 | invalid thumb size |
| 1038 | `fileids` not provided |
| 2000 | log in failed (bad credentials) |
| 2002 | a component of the parent directory does not exist |
| 2003 | access denied |
| 2005 | directory does not exist |
| 2009 | file not found |
| 2010 | invalid path |
| 2011 | speed limit too low / user is registered in EU |
| 2012 | user is registered in US |
| 2044 | video links can only be generated for videos |
| 2064 | 2FA code required |
| 2229 | invalid 2FA code |
| 3001 | could not create thumb from the given file |
| 4000 | too many login tries from this IP address |
| 5000 | internal error |
| 5002 | internal error, no servers available — retry later |

Note `2011`/`2012` are overloaded: on `getfilelink` they mean "speed limit too low",
on `userinfo` they mean "wrong data center". The app only maps them to a region
message on the login path.
