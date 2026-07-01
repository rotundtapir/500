# Google Mobile Ads / Play Billing linking exception

## Additional permission under GNU GPL version 3 section 7

The copyright holders give you permission to combine, link, and/or convey
this program (the "Program", licensed under the GNU General Public License
version 3 or, at your option, any later version) with the following
proprietary software components:

- the **Google Mobile Ads SDK** (`com.google.android.gms:play-services-ads`)
  and its transitive Google Play services / User Messaging Platform
  dependencies; and
- the **Google Play Billing Library** (`com.android.billingclient:billing`).

You may convey such a combination under terms of your choice, consistent with
the licensing of those proprietary components, provided that you comply with
the GNU GPL version 3 in all other respects for the covered work described in
the combination.

## Why this exception exists

The Program is distributed through two kinds of build:

- **Ad-supported builds** (e.g. the Google Play flavour) link the proprietary
  Google Mobile Ads SDK and Google Play Billing Library. The GPL is otherwise
  incompatible with linking against these non-free libraries; this exception
  makes such builds distributable.
- **Free/libre builds** (e.g. the F-Droid flavour) never link these
  components and are therefore plain GPLv3 works — this exception has no
  effect on them.

The exception applies to **all** code in this repository, including
contributions, so that any contributor's code can be shipped in an
ad-supported build without additional paperwork (see `CONTRIBUTING.md`).

## SPDX header

Source files in this project carry the header:

```
SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
```

`LicenseRef-cardkit-ads-exception` refers to the permission stated above.

---

> If you remove all use of the Google Mobile Ads SDK and Google Play Billing
> Library from a build, that build is governed solely by the GNU GPL v3 (or
> later) and this exception is simply unused.
