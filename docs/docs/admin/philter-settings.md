# Philter settings

**Admin → Philter → Settings** (`/admin/philter/settings`) shows and changes the settings of the
Philter that Philter Workspace uses. They apply to every Philter user. The page acts as you, so
Philter needs you to be a Philter administrator, and its audit log names you. Philter Workspace
auditors are Philter users, so Philter does not show them its settings. **Admin → Philter → Connection** is Philter Workspace's own
connection to Philter.

- **Multi-factor authentication**: whether people may set up MFA, and whether everyone must set it
  up at sign-in. MFA can be required only while it is available.
- **Output signing**: whether Philter signs every text redaction and explain response (the
  `X-Philter-Signature` header). The page shows the signing key's ID and fingerprint. Philter also
  signs every ledger entry with this key, whatever this setting. Regenerating the key is not offered
  here.
- **Webhook destinations**: the hosts people's webhooks may call, as hostnames, IP addresses, or
  CIDR ranges, separated by commas or lines. Empty allows any host except private, loopback, and
  other special-purpose addresses.
- **PII counts**: whether Philter records PII counts for differential-privacy reporting (Philter
  Diffuse), and whether it publishes them to Phield for drift monitoring, with Phield's URL,
  organization, source, and API key. The URL must be http or https, and is required to publish.
- **Phield API key**: write-only. The page says whether one is set and never shows it. Leave the
  field blank to keep it, enter a new one to replace it, or check **Remove the Phield API key**.
  Philter warns when the key would be sent over http.
- **Set by Philter's environment**: whether `ADMIN_CROSS_USER_ACCESS_ENABLED` and
  `LEDGER_DELETION_ENABLED` are on. They are read-only here.

Only the settings you change are sent, so a setting someone else changed while you had the page open
is not overwritten. Philter records saving in its audit log (`settings_updated`, naming the
settings that changed but not their values), readable from **Audit Log → Philter**.
