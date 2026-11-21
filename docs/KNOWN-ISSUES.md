# Known issues

- Some One UI phones may show two Backstage icons; Settings → *About* shows what Android reports for the app.
- **Locked mode** needs the platform to accept an always-unlocked display from the shell; some vendors refuse it.
  The *Locked mode* row in Settings shows what was applied.
- Accessibility trees are available only on Dextop's direct virtual display, which Backstage uses.
- Apps with `FLAG_SECURE` render black in screenshots; some apps refuse secondary displays.
- Some Dextop screens still say "Dextop"; the Linux settings text names `/data/local/tmp/dextop/linux` (Backstage
  uses `/data/local/tmp/dextop-com.eggman.backstage/linux`).
- Google Play services for Cast still ship (Dextop's Cast feature). See [`NOTICE.md`](../NOTICE.md).
