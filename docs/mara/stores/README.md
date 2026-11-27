# MARA in the app stores (MARA Stage 10, M-NF-13)

What to enter in Google Play Console and App Store Connect, and how firms can distribute the app privately. The builds come from `.github/workflows/mobile-release.yml` ([field-test/distribution.md](../field-test/distribution.md)). Exit criterion: store review passed on both platforms.

| Document | Content |
|---|---|
| [listing.md](listing.md) | Store texts (German first, English), screenshots, category, contact |
| [privacy-labels.md](privacy-labels.md) | Google Play "Data safety" and Apple "App Privacy" answers |
| [review-notes.md](review-notes.md) | Notes and the demo account for the reviewers |
| [mdm.md](mdm.md) | Private distribution through MDM (Managed Google Play, Apple Business Manager) |

## Checklist

| Step | Google Play | Apple | Done |
|---|---|---|---|
| App record (`de.erika.mara`) | Play Console | App Store Connect | ☐ |
| Listing texts and screenshots ([listing.md](listing.md)) | Main store listing | App Information, version page | ☐ |
| Privacy policy URL `https://<domain>/datenschutz` | App content → Privacy policy | App Privacy | ☐ |
| Data safety / App Privacy ([privacy-labels.md](privacy-labels.md)) | App content → Data safety | App Privacy | ☐ |
| Content rating questionnaire | IARC: business app, no user-generated public content | Age rating 4+ | ☐ |
| Target audience | 18+, not for children | – | ☐ |
| Ads | No ads | – | ☐ |
| Demo account and review notes ([review-notes.md](review-notes.md)) | App access | App Review Information | ☐ |
| Export compliance | – | Standard encryption only (HTTPS, SQLCipher): exempt, confirm | ☐ |
| Production release (staged: 20 % → 100 %) | Production track | Phased release | ☐ |
| Review passed | date: | date: | ☐ |
