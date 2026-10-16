

<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="128" alt="MemosM App Icon" />

# MemosM

A feature-rich Android client for Memos.

![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/yamada-sexta/memos-m/canary-build.yml?style=flat-square)
![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/yamada-sexta/memos-m/total?style=flat-square)
![GitHub License](https://img.shields.io/github/license/yamada-sexta/memos-m?style=flat-square)
![GitHub Repo stars](https://img.shields.io/github/stars/yamada-sexta/memos-m?style=flat-square)
![GitHub Issues or Pull Requests](https://img.shields.io/github/issues/yamada-sexta/memos-m?style=flat-square)

</div>

---

**MemosM** is an Android client designed to target the latest stable release of [Memos](https://usememos.com/) (currently v0.28.x - v0.31.x).

It focuses on providing a native experience, speed, and full support for the latest features of the Memos ecosystem.

## Screenshots

<img height="300"  alt="image" src="https://github.com/user-attachments/assets/aba051d0-0c6d-4d0a-9bd0-9b7d0389d000" />
<img height="300"  alt="image" src="https://github.com/user-attachments/assets/c1c3ac79-1d17-402a-b538-1eafe6b624e8" />

## Why MemosM?

While other clients like [MoeMemos](https://github.com/mudkipme/MoeMemosAndroid) are available, MemosM was developed to address specific technical and user experience gaps:

- **Native Android Focus:** MemosM is built specifically for Android rather than being a cross-platform port. This ensures better performance and robust tablet support.
- **Efficiency:** Optimized syncing prevents high data usage and ensures a faster, more responsive experience compared to clients that re-sync entire histories.
- **Modern Feature Set:** By focusing on recent Memos versions, we avoid the limitations of backward compatibility and can implement modern features like advanced Markdown and Reactions.

## SSO login

Select **SSO**, enter your Memos server URL, and choose **Find providers**. Select a provider
to authenticate in your browser. MemosM then creates a personal access token for the account,
just as password login does. Password and manual token login remain available.

An administrator must add the app callback URI to the allowed redirect URIs for the OAuth2
client configured in Memos. Keep the existing Memos web callback registered as well.

| Build | Callback URI |
| --- | --- |
| Release | `org.example.memosm://oauth/callback` |
| Insider | `org.example.memosm.insider://oauth/callback` |
| Canary | `org.example.memosm.canary://oauth/callback` |
| Debug | `org.example.memosm.debug://oauth/callback` |

The provider must accept custom URI schemes and S256 PKCE. Providers requiring an HTTPS
callback can use manual token login instead. SSO supports the existing v0.26–v0.31 API
adapters and the newer Memos SSO format. Registration and identity provisioning follow
the server's settings. Sign-in attempts expire after ten minutes; if Android terminates
the app during authentication, start sign-in again.

## Downloads

### Stable Build

> MemosM is still under active development, so there is no stable build for now.

### Insider Build

The Insider build is designed for users who want to test out relatively new features without facing constant instability. While generally usable, it may still contain bugs and unpolished features.

[<img src="https://github.com/user-attachments/assets/713d71c5-3dec-4ec4-a3f2-8d28d025a9c6"
     alt="Get it on Obtinium"
     height="80">](<https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22org.example.memosm.insider%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fyamada-sexta%2Fmemos-m%22%2C%22author%22%3A%22yamada-sexta%22%2C%22name%22%3A%22MemosM%20(Insider)%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22filterReleaseTitlesByRegEx%5C%22%3A%5C%22Insider%20Build*%5C%22%2C%5C%22filterReleaseNotesByRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22verifyLatestTag%5C%22%3Afalse%2C%5C%22sortMethodChoice%5C%22%3A%5C%22date%5C%22%2C%5C%22useLatestAssetDateAsReleaseDate%5C%22%3Afalse%2C%5C%22releaseTitleAsVersion%5C%22%3Afalse%2C%5C%22trackOnly%5C%22%3Afalse%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22matchGroupToUse%5C%22%3A%5C%22%5C%22%2C%5C%22versionDetection%5C%22%3Afalse%2C%5C%22releaseDateAsVersion%5C%22%3Afalse%2C%5C%22useVersionCodeAsOSVersion%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22invertAPKFilter%5C%22%3Afalse%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%2C%5C%22appName%5C%22%3A%5C%22%5C%22%2C%5C%22appAuthor%5C%22%3A%5C%22%5C%22%2C%5C%22shizukuPretendToBeGooglePlay%5C%22%3Afalse%2C%5C%22allowInsecure%5C%22%3Afalse%2C%5C%22exemptFromBackgroundUpdates%5C%22%3Afalse%2C%5C%22skipUpdateNotifications%5C%22%3Afalse%2C%5C%22about%5C%22%3A%5C%22%5C%22%2C%5C%22refreshBeforeDownload%5C%22%3Afalse%2C%5C%22includeZips%5C%22%3Afalse%2C%5C%22zippedApkFilterRegEx%5C%22%3A%5C%22%5C%22%7D%22%2C%22overrideSource%22%3Anull%7D>)

### Canary Build

The Canary build is the "bleeding edge" version. It contains the very latest code, features, and improvements, but it carries a higher risk of major regressions or crashes.

**Expect: Frequent updates, experimental changes, and potential instability.**

[<img src="https://github.com/user-attachments/assets/713d71c5-3dec-4ec4-a3f2-8d28d025a9c6"
     alt="Get it on Obtinium"
     height="80">](<https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22org.example.memosm.canary%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fyamada-sexta%2Fmemos-m%22%2C%22author%22%3A%22yamada-sexta%22%2C%22name%22%3A%22MemosM%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22filterReleaseTitlesByRegEx%5C%22%3A%5C%22Latest*%5C%22%2C%5C%22filterReleaseNotesByRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22verifyLatestTag%5C%22%3Afalse%2C%5C%22sortMethodChoice%5C%22%3A%5C%22date%5C%22%2C%5C%22useLatestAssetDateAsReleaseDate%5C%22%3Atrue%2C%5C%22releaseTitleAsVersion%5C%22%3Afalse%2C%5C%22trackOnly%5C%22%3Afalse%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22matchGroupToUse%5C%22%3A%5C%22%5C%22%2C%5C%22versionDetection%5C%22%3Afalse%2C%5C%22releaseDateAsVersion%5C%22%3Afalse%2C%5C%22useVersionCodeAsOSVersion%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22invertAPKFilter%5C%22%3Afalse%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%2C%5C%22appName%5C%22%3A%5C%22MemosM%20(Canary)%5C%22%2C%5C%22appAuthor%5C%22%3A%5C%22%5C%22%2C%5C%22shizukuPretendToBeGooglePlay%5C%22%3Afalse%2C%5C%22allowInsecure%5C%22%3Afalse%2C%5C%22exemptFromBackgroundUpdates%5C%22%3Afalse%2C%5C%22skipUpdateNotifications%5C%22%3Afalse%2C%5C%22about%5C%22%3A%5C%22A%20lightweight%2C%20feature-rich%20Android%20client%20for%20Memos.%5C%22%2C%5C%22refreshBeforeDownload%5C%22%3Afalse%2C%5C%22includeZips%5C%22%3Afalse%2C%5C%22zippedApkFilterRegEx%5C%22%3A%5C%22%5C%22%7D%22%2C%22overrideSource%22%3Anull%7D>)

You can also download from [release](https://github.com/yamada-sexta/memos-m/releases/tag/latest) directly.

On Android 17 and later, connecting to a local Memos server may require the **Nearby devices** permission. MemosM requests it when you sign in to a local server or open a saved local account. If access was denied, use **Local network access** on the login screen or in app settings to enable it. Public servers do not require this permission.

---

## Features

- User
  - Auth
    - [x] Login with token
    - [x] Login with password
  - User info
    - [x] View user info
    - [x] Edit user info
    - [x] Activities
- Memos
  - Basic
    - [x] View/post/edit Memos
    - [x] Comment on Memos
    - [x] Search Memos
  - Markdown
    - [x] Basic rendering
    - [x] Checkboxes
    - [x] Codeblocks with syntax highlighting
    - [x] Table
    - [x] Basic LaTeX
  - Attachment
    - [x] View attachments
    - [x] Large attachment handle (blocking)
    - [x] Video playback
    - [x] Audio playback
    - [x] View image
  - Reactions
    - [x] View reactions
    - [x] Add/Remove reactions
    - [x] Respect server emoji list
  - Map
    - [x] Location
    - [x] Map view
- Notifications
  - [x] View notifications
  - [ ] ~~Fetch notifications in the background~~ (bad for battery + no demand)
- Misc
  - [x] Native map with Memos/Explore scopes, location pins, filters, and composing at a selected place
  - [x] Multi language support: English, Japanese, Chinese, Korean, German, Polish
  - [x] Sharing intent support
  - [x] Local cache
  - [x] Offline-first
    - [x] Full offline read
    - [x] Offline writes queued
    - [x] Offline media store
    - [x] Auto-sync on reconnect

The Map destination appears only after the connected instance accepts the `has_location`
memo filter. Older and unknown instances do not show it. Previously verified accounts can
browse cached locations offline using available cached tiles. Saved views require a connection;
map regions are not downloaded in advance. Basemaps use OpenFreeMap, with OpenStreetMap
as a fallback.

## Non-goals

- **Indefinite Backward Compatibility:** We prioritize compatibility with the latest Memos API to leverage modern features. Older versions of Memos will not be supported for long.
- **Multiplatform Support:** MemosM is built exclusively for Android to ensure a high-quality, native experience.

## Contributing

Contributions are welcome in the form of code, bug reports, or feature suggestions.

- **Issues:** Open an issue to discuss bugs or design ideas.
- **Pull Requests:** Focused, easy-to-review PRs are appreciated.
- **Translations:** Currently there isn't a good contribution workflow, but you can make RPs of the `string.xml`.

## Related Projects

- **Official Memos Project:** [usememos.com](https://usememos.com/)
- **MoeMemos Android:** [github.com/mudkipme/MoeMemosAndroid](https://github.com/mudkipme/MoeMemosAndroid)

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=yamada-sexta/memos-m&type=date&legend=top-left)](https://www.star-history.com/#yamada-sexta/memos-m&type=date&legend=top-left)

## Backup and restore

Export configurable `.mmbackup` files with MessagePack, gzip, and optional password protection.
Restore selected accounts, settings, cached server data, media, and unpublished drafts. Queued
edits are optional and unchecked by default; restored edits are kept for review and never sync
automatically. Android Auto Backup restores account information during device setup, while
direct device transfer also preserves caches and drafts. The server remains authoritative.
See [backup format and verification](docs/backup.md) for details.
