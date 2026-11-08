# Pi-hole Viewer

An Android client for **Pi-hole v6**, so you can pause ad blocking without opening the web
admin page on every instance you run.

It started as one small convenience — a pause button — and grew from there. It is a
personal project, built to scratch an itch, and it is being prepared for release on
Google Play.

## What it does

- **Instances** — keep a list of the Pi-holes you run, each with a name, a URL and its web
  admin password. A selector across the top switches which one you are looking at.
- **Dashboard** — a combined view across every instance: total queries, total blocked, the
  combined percentage, and a breakdown card for each Pi-hole.
- **Control** — enable or disable blocking, or pause it for a set number of minutes and watch
  the timer count down. Pausing is time-boxed: Pi-hole switches blocking back on by itself when
  the timer expires. Disabling has no timer and stays off until you turn it back on. Either works
  on the instance you have selected, or on every one of them at once.
- **Query Log** — recent queries, so you can see what is actually being blocked and by
  which client. One instance at a time — switch with the selector to compare.
- **Lists** — view and add blocklists, and trigger a gravity update. Also one instance at a
  time, so you switch between Pi-holes to work on each one's lists.

## Requirements

- **Pi-hole v6.** The app speaks the v6 REST API — `POST /api/auth` to get a session, then
  `/api/dns/blocking`, `/api/stats/summary`, `/api/queries` and `/api/lists`. Pi-hole v5
  served everything from a single `/admin/api.php` endpoint with a token in the query
  string. There is nothing there for this app to call, so v5 will not work — the problem
  is not the credential you type, it is that the endpoints do not exist.
- **Android 8.0** (API 26) or newer.
- The **web admin password** for the Pi-hole you want to reach. Pi-hole v6 issues a
  session from that password, and it is the same one you would type into the web
  interface — there is no separate API key to create.

## How connections work

**Where the password goes.** It is stored on the device, encrypted with a key held in the
Android Keystore (AES-256-GCM). It is sent to the Pi-hole you configured and nowhere else.
The app excludes what it stores from Android's cloud backup and device transfer.

**HTTPS and certificates.** HTTPS is the sensible choice, and it is what the app assumes if
you type a bare address. Pi-holes commonly serve a self-signed certificate, which your phone
cannot verify on its own — so on first connection the app shows you that certificate's
SHA-256 fingerprint and asks whether to trust that specific one. If you accept, it remembers
it for that instance. If you decline, it will not connect: it does not quietly accept a
certificate you did not agree to. You can also paste a known fingerprint when adding an
instance.

**Plain HTTP.** You can enter an `http://` address and it will work, but the traffic —
including the password — is unencrypted on your network. The app warns you when you do.

## Privacy

No analytics, no crash reporting, no advertising, no accounts. The only permission the app
requests is `INTERNET`, and it exists so the app can reach the Pi-hole you point it at.
There is no server in the middle, because there is no server at all.

Full policy: https://www.dev-adam.com/pihole-viewer/privacy/

## Building

Open the project in Android Studio, or build from a terminal with a JDK installed:

```bash
./gradlew assembleDebug
```

Debug builds install and run with no extra setup. The app is Kotlin with Jetpack Compose and
Material 3, built with Gradle 9.1.0 and AGP 8.13.2; `compileSdk`/`targetSdk` are 36 and
`minSdk` is 26.

**Release builds** additionally need signing details in a `keystore.properties` file at the
project root. That file is gitignored — please keep it that way:

```properties
storeFile=path/to/your.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Release builds also enable R8 minification and resource shrinking. Those have not been
exercised as heavily as a debug build, so if something works in debug and fails only in
release, minification is the first suspect.

## Not affiliated with Pi-hole

This is an unofficial third-party client. Pi-hole is a separate project, and this app has
nothing to do with it beyond talking to its API.

## License

MIT — see [LICENSE](LICENSE).
