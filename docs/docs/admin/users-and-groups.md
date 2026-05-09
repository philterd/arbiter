# Users and groups

Both pages live under **Admin** and are restricted to the `ADMIN`
role.

## Users (`/admin/users`)

The user list shows every account, sorted by email. The current admin's row is
marked **(you)** — you cannot delete yourself.

### Add a user

The Add-user form supports two flows; pick one per user.

**1. Set an initial password yourself (works without SMTP).** Fill in
**Email**, type an **Initial password** (at least 12 characters), optionally
tick **Admin**, and click **Add user**. The account is created immediately
and the password you typed is stored as a hash — Arbiter never keeps
plaintext passwords. **Communicate the password to the recipient
out-of-band** (direct message, phone, in person). On their first sign-in
they are redirected to **Settings → Change password** and cannot reach any
other page until they choose a new password. This is the recommended flow
when outbound email is not configured.

**2. Send a one-time invitation link by email.** Fill in **Email**, leave
the **Initial password** field blank, optionally tick **Admin**, and click
**Add user**. Arbiter generates a single-use token, stores an `Invitation`
record, and emails a link to the recipient. The recipient sets their own
password by following the link. Outbound email must be configured under
[Notifications](../admin/notifications.md) — without SMTP this flow is
refused, with an error message pointing you back to the Initial-password
flow above.

In both flows the email is validated for shape (`local@domain.tld`) and
lowercased before storage. After signing in, the new user has access to the
batches and documents in any group they belong to (or all of them, if
**Admin** was checked).

### Edit a user

Each row has a **Role** select and a **New password (optional)** field.

- The Role select offers **User**, **Admin**, and **Auditor (read-only)**.
  Auditor is the cross-group read-only role — see
  [Roles and permissions](../reference/roles.md) for what each role can do.
  Any direction of role change is allowed (subject to the safeguards below).
- Leaving New password blank keeps the existing password. Filling it in
  replaces the password (still validated to ≥ 12 characters).

**Safeguards on the Edit form:**

- **You cannot edit your own account from this page.** Your own row's edit
  form is hidden and replaced with a link to your personal Settings page.
  Use Settings to change your own password, manage 2FA, or rotate your API
  key — those flows require your current password as a re-auth check, which
  the admin Edit form bypasses by design.
- **The last admin cannot be demoted.** Setting the only remaining
  administrator's role to **User** *or* **Auditor** is rejected with an error
  (*"Cannot remove admin from … : at least one administrator is required"*)
  to prevent locking the entire deployment out of `/admin/**`. Add a second
  admin first, then demote. Note that Auditor is a read-only role and does
  **not** count toward the admin tally — converting your last admin to an
  auditor would leave nobody able to mutate state, so the same guard fires.

### Reset a user's password

Arbiter has no automated password-reset email flow. When a user forgets their
password, an admin must set a new one manually:

1. Go to **Admin → Users**.
2. Find the user's row.
3. Type a new password (at least 12 characters) into the **New password
   (optional)** field. Leave the Admin checkbox as-is unless you also intend
   to change the role.
4. Click **Save**.
5. Communicate the new password to the user through a secure out-of-band
   channel (direct message, phone call, etc.). Arbiter does not automatically
   email a reset notification — the "Email login information" option is
   available only when *creating* a new user, not on resets.

The change takes effect immediately; the user's next login attempt will use
the new password. The user is then **forced to choose a new password**
before reaching any other page — the password the admin set is meant to be
a one-time hand-off, not a long-lived credential.

A `USER_UPDATE` event is written to the [audit log](audit-log.md) with
`passwordReset: true`, the user's email, and the acting admin's identity.

#### Resetting your own password as an admin

If you are the only admin and have forgotten your own password, you cannot
use the Admin → Users page (you cannot log in). Options:

- Another admin account, if one exists, can reset it via the Users page.
- Otherwise, update the `passwordHash` field on your user document directly
  in MongoDB. Generate a replacement BCrypt hash and prefix it with
  `{bcrypt}` so the encoder routes it correctly — for example,
  `htpasswd -nbBC 12 '' '<your-new-password>' | sed -e 's/^://' -e 's/^/{bcrypt}/'`.
  See [Security · Password storage](../security.md#password-storage) for the
  encoder's full format. Change the password again from Settings as soon as
  you can sign in, so the rotation is captured in the audit log.

#### Locked out by MFA as well

If a user has MFA enabled *and* has lost their authenticator device, resetting
their password alone is not enough — they will still be redirected to the TOTP
challenge after entering the new password. In this case the `mfaEnabled` flag
and `totpSecret` field must be cleared on the user's MongoDB document directly
(the Users page does not currently expose a "Clear MFA" action). Once cleared,
the user can log in with their password and re-enroll in MFA from their
[Settings page](../user-guide/settings.md#two-factor-authentication).

### Delete a user

Use **Delete**. You cannot delete your own account.

Deleting a user does **not** delete batches they own or documents they
uploaded — those records reference the email at the time of action and
remain intact. Group memberships are not automatically pruned but are
ignored when computing access.

## Groups (`/admin/groups`)

A **group** holds zero or more users; every batch is assigned to exactly one
group. Group membership is what scopes a `USER` to a subset of batches and
documents.

### Create a group

Set the **Name** and check at least one user under **Members**. Names must be
unique. A group must always have at least one member.

### Edit a group

Each group row has an inline form to rename the group and update its members.
Removing the last member is rejected with an error.

### Delete a group

Click **Delete** on a group row. Deleting a group does **not** delete batches
that reference it; instead, those batches will appear with no group and be
inaccessible to non-admins until reassigned. Reassign batches first if you
want to avoid orphaning them.

## Defaults

On any start where no admin account exists in the database, Arbiter
creates a single admin (`admin@philterd.ai`). The password is taken from
the **`ARBITER_ADMIN_INITIAL_PASSWORD`** environment variable when that
variable is set to a value of at least 12 characters; otherwise Arbiter
generates a random password and prints it to standard output. A
generated password is flagged for forced rotation at first login (since
it was briefly visible in stdout); a password supplied via
`ARBITER_ADMIN_INITIAL_PASSWORD` is not — the operator already controls
where that value lives. See
[Getting started → First run](../getting-started.md#first-run) for the
full banner format. On first start with empty `groups` and the demo
loader enabled, Arbiter creates a `Default` group containing every
existing user and assigns the seeded sample-files batch to it. All of
this is placeholder configuration — sign in, rotate the password, and
add real users and groups before going live.
