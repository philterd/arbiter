# Security settings

The **Security** tab in Admin (`/admin/security`) contains policies that apply to all users
across the deployment.

## Require MFA for all users

When this checkbox is checked and saved, Arbiter enforces multi-factor authentication for every
account:

- Users who already have an authenticator app configured are unaffected — they continue through
  the normal [TOTP login flow](../user-guide/settings.md#login-flow-with-2fa).
- Users **without** MFA configured are redirected to the setup page (`/settings/mfa/setup`)
  immediately after entering their password. They cannot access any other page until they
  complete enrollment. A yellow banner on the setup page explains why they are there.
- The policy is enforced by `MfaEnrollmentInterceptor` on every request. It re-checks the
  database on each request, so disabling the policy takes effect for the next request without
  requiring a restart.

### Enabling the requirement

1. Navigate to **Admin → Security**.
2. Check **Require MFA for all users**.
3. Click **Save**. An amber warning banner confirms the policy is active.

!!! warning
    Before enabling this policy, communicate the change to your users and allow time for
    them to install an authenticator app (Google Authenticator, Authy, Microsoft Authenticator,
    etc.). Users who log in before setting up MFA will be sent to the setup wizard automatically,
    but they will need a phone or device with an authenticator app available at that moment.

### Disabling the requirement

Uncheck **Require MFA for all users** and click **Save**. Users without MFA configured can
then log in with password alone. Users who already enrolled are not affected — their MFA
remains active until they choose to disable it from their own [Settings page](../user-guide/settings.md#disabling-2fa).

### If a user is locked out

If a user loses their authenticator device and cannot produce a valid TOTP code, the admin
must clear the `mfaEnabled` flag and `totpSecret` field on the user's MongoDB document
directly — the Users page does not currently expose a "Clear MFA" action. Once cleared, the
user can log in with their password and re-enroll from their
[Settings page](../user-guide/settings.md#two-factor-authentication). If the user has also
forgotten their password, reset it first (see
[Resetting a user's password](users-and-groups.md#reset-a-users-password)) before clearing
MFA.

## Data-source host allow-list

Arbiter ships with a host allow-list that gates outbound connections to
external systems — OpenSearch, Elasticsearch, Philter, Ollama, and any
other URL an admin types into a Test button or saves on a data source.
The allow-list combines two protections: a **default-deny on private-range
addresses** (RFC-1918, loopback, and link-local) and an **operator-supplied
allow-list of public hosts** configured via the
`arbiter.data-sources.allowed-hosts` property. Both are documented in
[Data sources → Trust model and host allow-list](data-sources.md#trust-model-and-host-allow-list).

The **Enable host allow-list** checkbox on **Admin → Security** is the
master switch for both protections.

| State | Behavior |
|---|---|
| **Enabled** (default) | Outbound endpoints are checked against the configured allow-list and the private-range default-deny. Hosts that don't match are refused with the *"Endpoint host is not on the data-source allow-list"* error. |
| **Disabled** | The allow-list is bypassed entirely. Any well-formed URL an admin enters is accepted. The private-range default-deny is also bypassed. |

The setting is read at request time, so toggling it takes effect on the
next request without a restart.

### Enabling the allow-list

This is the default state for new installs. To re-enable after disabling:

1. Navigate to **Admin → Security**.
2. Check **Enable host allow-list**.
3. Click **Save**.

### Disabling the allow-list

Uncheck **Enable host allow-list** and click **Save**. A red banner
appears on the Security page confirming the switch is off.

!!! danger "Disabling is not recommended"
    The host allow-list is the primary defence against
    **server-side request forgery (SSRF)** through the admin UI. With the
    allow-list off:

    - Any admin (or anyone who has compromised an admin session) can use
      the data-source Test buttons, ingest workers, or Philter/Ollama
      probes to reach **internal infrastructure** the Arbiter process
      can route to — internal metadata services, intranet HTTP servers,
      bastion hosts, or other private-range systems that should not be
      reachable from operator input.
    - Misconfigured or hostile data-source rows can be saved against
      arbitrary public endpoints — the operator-supplied URL is no longer
      sanity-checked against a curated list.
    - Cloud instance metadata endpoints (`169.254.169.254`,
      `metadata.google.internal`, etc.), which are private-range by
      address, become reachable when they otherwise wouldn't be.

    Disable only as a temporary measure for a known-good, short-lived
    troubleshooting session. Configure
    `arbiter.data-sources.allowed-hosts` to cover the legitimate hosts
    you need to reach, and turn the allow-list back on as soon as
    practical. Toggling produces a `SECURITY_SETTINGS_CHANGE` audit row
    so the off-window is auditable after the fact.

## Audit trail

All changes to security settings are recorded in the [audit log](audit-log.md):

| Event | Trigger |
|---|---|
| `SECURITY_SETTINGS_CHANGE` | Admin saves the Security settings form (logged whether or not the value changed); payload names which switch was flipped (`requireMfa` or `hostAllowListEnabled`) and its previous value |
| `MFA_ENABLED` | A user successfully completes MFA setup |
| `MFA_DISABLED` | A user disables MFA from their Settings page |
