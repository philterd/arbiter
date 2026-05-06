# Users and groups

Both pages live under **Admin** and are restricted to the `ADMIN`
role.

## Users (`/admin/users`)

The user list shows every account, sorted by email. The current admin's row is
marked **(you)** — you cannot delete yourself.

### Create a user

Fill in **Email** and **Password**, optionally tick **Admin**, and click
**Create user**.

- The email is validated for shape (`local@domain.tld`) and lowercased before
  storage.
- The password must be at least 4 characters. It is stored as a salted
  SHA-512 hash.

The user can sign in immediately. They have access to the batches and
documents in any group they belong to (or all of them, if **Admin** is
checked).

### Edit a user

Each row has an **Admin** checkbox and a **New password (optional)** field.

- Toggling Admin changes the role on save. Both directions are allowed.
- Leaving New password blank keeps the existing password. Filling it in
  replaces the password (still validated to ≥ 4 characters).

### Reset a user's password

Arbiter has no automated password-reset email flow. When a user forgets their
password, an admin must set a new one manually:

1. Go to **Admin → Users**.
2. Find the user's row.
3. Type a new password (at least 4 characters) into the **New password
   (optional)** field. Leave the Admin checkbox as-is unless you also intend
   to change the role.
4. Click **Save**.
5. Communicate the new password to the user through a secure out-of-band
   channel (direct message, phone call, etc.). Arbiter does not automatically
   email a reset notification — the "Email login information" option is
   available only when *creating* a new user, not on resets.

The change takes effect immediately; the user's next login attempt will use
the new password.

A `USER_UPDATE` event is written to the [audit log](audit-log.md) with
`passwordReset: true`, the user's email, and the acting admin's identity.

#### Resetting your own password as an admin

If you are the only admin and have forgotten your own password, you cannot
use the Admin → Users page (you cannot log in). Options:

- Another admin account, if one exists, can reset it via the Users page.
- Otherwise, update the `passwordHash` field on your user document directly
  in MongoDB. Generate a replacement hash with the same salted SHA-512 scheme
  (see [Security](../security.md#password-storage)), or temporarily set a
  known hash and change it immediately after login.

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

On first start with an empty `users` collection, Arbiter creates a single
admin: `admin@philterd.ai` / `admin`. On first start with empty
`groups` and the demo loader enabled, Arbiter creates a `Default` group
containing every existing user and assigns the seeded sample-files batch
to it. Both are placeholders — change the password and add real users and
groups before going live.
