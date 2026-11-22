# 0013. Spike S1-a: Object Lock at the production storage provider

- Status: Accepted
- Date: 2026-11-22

## Question
Does Hetzner Object Storage support S3 Object Lock in compliance mode (Software Design, open point 1)?

## Findings (Hetzner documentation, read 2026-11-22)
- Object Lock supports `GOVERNANCE` and `COMPLIANCE` mode; compliance mode can't be ended early.
- Object Lock must be enabled **when the bucket is created**; it can't be added later.
- Per-object retention (`put-object-retention` with `RetainUntilDate`), bucket default retention and legal hold (`put-object-legal-hold`) are documented; no Object Lock action is listed as unsupported.
- Caveats: S3 compatibility is described as "basic"; changes can take a few minutes to apply; data is kept only while the contract is active (the FAQ on compliance-mode objects at termination is still to be read); no compliance certification (e.g. SEC 17a-4) is stated.

Sources: [Object Lock retention](https://docs.hetzner.com/de/storage/object-storage/howto-protect-objects/protect-object-lock-retention), [legal hold](https://docs.hetzner.com/storage/object-storage/howto-protect-objects/protect-object-lock-legal-hold/), [supported actions](https://docs.hetzner.com/storage/object-storage/supported-actions/).

## Decision
Keep Hetzner Object Storage. Create staging and production originals buckets **with Object Lock enabled from the start**. Stage 4 sets per-object retention in COMPLIANCE mode to `retention_until`. The design's fallback (app key without delete permission) also applies, as defence in depth.

## Follow-ups
- Stage 4: integration test against a real Hetzner staging bucket (put with retention; delete attempt rejected).
- Read Hetzner's FAQ on compliance-mode objects at contract termination and reflect it in the Verfahrensdokumentation.
