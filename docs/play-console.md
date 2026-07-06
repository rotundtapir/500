# Play Console setup — declarations cheat sheet

Paste-ready answers for the app-content declarations of the **play flavor** (the only flavor that
reaches Google Play). Grounded in Google's published SDK disclosures as of 2026-07-06 — re-check
them whenever the Google Mobile Ads SDK is upgraded:

- AdMob: <https://developers.google.com/admob/android/privacy/play-data-disclosure>
- Data safety form reference: <https://support.google.com/googleplay/android-developer/answer/10787469>

What the app itself does, for context: no accounts, no analytics or crash-reporting SDKs, no
server of ours. Settings live in on-device DataStore (on-device processing — not "collected" in
the form's sense). The only data leaving the device is what the Google Mobile Ads SDK (with UMP
consent) and Google Play billing send to Google.

## Data safety form

**Overview questions**

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (TLS) |
| Do you provide a way for users to request that their data is deleted? | **No** — the app has no accounts and collects no data itself; the ads data is controlled via the device's advertising-ID reset/delete and the in-app "Privacy options" (UMP) form |

**Data types** — all four rows come from the Google Mobile Ads SDK; the app adds nothing of its
own. For each: **Collected: Yes · Shared: Yes** (shared with Google as the ad provider),
**Processed ephemerally: No**, **Required (not optional)**, purposes = **Advertising or
marketing, Analytics, Fraud prevention, security, and compliance**.

| Category | Data type | Notes |
| --- | --- | --- |
| Location | Approximate location | IP-derived coarse location used for ad serving |
| App activity | App interactions | App launches, taps, video views seen by the ads SDK |
| App info and performance | Diagnostics | Launch time, hang rate, energy usage |
| Device or other IDs | Device or other IDs | Android advertising ID, app set ID |

For Diagnostics specifically, purposes are **Analytics** and **Fraud prevention, security, and
compliance** (it is not used for advertising).

**Play billing (`remove_ads`)**: nothing to declare. Google Play's billing system processes the
transaction itself, and payment data collected by a payment service in the course of processing
falls under the form's payment-service exemption. The app never sees or stores payment details or
purchase history; it only checks the entitlement.

**Not collected** (leave every other category unchecked): personal info, financial info, health,
messages, photos/videos, audio, files, calendar, contacts, browsing history, search history.

## Other app-content declarations

| Section | Answer |
| --- | --- |
| Package name | `io.github.rotundtapir.fivehundred` (locked in by the first AAB upload) |
| Automatic protection (App integrity) | **Off** — it blocks installs from outside Play, and this app is deliberately multi-channel (F-Droid, GitHub releases) and open source |
| Privacy policy | `https://github.com/rotundtapir/500/blob/main/PRIVACY.md` |
| Ads | **Yes, contains ads** (banner + one interstitial per game) |
| App access | All functionality available without special access (no login) |
| Content rating questionnaire | Category: Game → Card. No violence, sexuality, profanity, drugs, or user-generated content/chat. **No gambling**: no real-money wagering, no simulated gambling (trick-taking scores only, nothing is staked or cashed out), no loot boxes. Expect Everyone / PEGI 3 |
| Target audience | **13+ only** (do not tick under-13 brackets — keeps the app out of the Families-policy ad regime) |
| News app | No |
| COVID-19 tracing/status | No |
| Data safety ↔ ads consistency | The "contains ads" flag, this data-safety form, and the UMP consent flow must stay in agreement |
| Government app | No |
| Financial features | None of the listed features |
| Health apps | Not a health app |

## Store listing assets

Everything under `fastlane/metadata/android/en-US/`: `title.txt` ("500 - Card game"),
`short_description.txt`, `full_description.txt`, `images/phoneScreenshots/` (8 shots — Play's
maximum — ordered most-interesting-first: 1 = trick in progress, 2 = game-over score sheet,
3 = bidding, 4 = hand-result breakdown, 5 = six-player trick grid, 6 = deal animation,
7 = tutorial, 8 = home/mode picker), plus:

- `images/icon.png` — 512×512 store icon (mascot on the table-green felt, opaque; Play applies
  its own corner mask)
- `images/featureGraphic.png` — 1024×500 feature graphic

Source art: `art/rotund_tapir.png` (1024×1024). Regenerate the two store assets from it if the
branding changes.
