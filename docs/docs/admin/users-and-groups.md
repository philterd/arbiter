# Users and groups

Both pages live under **Admin** and are restricted to the `ADMIN`
role.

## Users (`/admin/users`)

People sign in through Philter (see
[Sign-in through Philter](../security.md#sign-in-through-philter)), so the Users page
lists Philter's users and changes them through Philter's Users API, acting as you:
Philter checks that you are an administrator, and its audit log names you. Arbiter
keeps each person's Arbiter role (User or Auditor) and groups. Auditors can open
the page, but Philter shows its users only to Philter administrators.

### Add a Philter user

Enter a username, an optional email address, and a first password of at least 16
characters, and tick **Administrator** to make them a Philter administrator. Give
the password to the person yourself; Philter makes them choose their own at first
sign-in. Arbiter creates their Arbiter record at once, so you can add them to
groups before they sign in. A username cannot contain `/`, `\`, `;`, `%`, or
control characters, and cannot be reused, even after the user is deactivated.

### In Arbiter

- **Auditor (read-only)** makes a user an Arbiter `AUDITOR`. A Philter
  administrator is always an Arbiter `ADMIN`.
- **Groups** sets the person's groups. A group needs at least one member, so you
  cannot remove a group's only member here. Removing someone from a group also
  removes them as its team lead. Team leads are chosen on the Groups page.

Saving creates the person's Arbiter record if they have not signed in yet. A user
added in Philter directly appears in the list, marked "Not signed in yet", and gets
a record when you save here or when they first sign in.

### In Philter

- **Set role** makes the person a Philter administrator or user. Philter keeps at
  least one active administrator.
- **Reset password** sets a new password, which the person must change at next
  sign-in, and ends their sessions.
- **Set email** changes the email address Philter keeps for them.
- **Clear sign-in lock** clears a lock from repeated failed sign-ins. Philter
  does not report whether a user is locked, so this says when there was no lock.
- **Unlock 2FA** and **Remove 2FA** are for a person locked out of two-factor
  authentication or who has lost their device. After removal they set it up again
  at next sign-in if your deployment requires it.
- **Sign out everywhere** ends all of the person's sessions.
- **Deactivate** stops the person signing in and their API keys working, and
  ends their sessions. Philter does not delete users, so **Reactivate** restores
  them. Their Arbiter record is kept, so batches, reviews, and the audit log still
  name them, and their groups return on reactivation.

Changing someone's role, resetting their password, deactivating them, or signing
them out also ends their Arbiter sessions.

You cannot change your own role, password, or two-factor authentication, or
deactivate yourself, here; use **Settings** for your password, or ask another
administrator.

### Arbiter records without a Philter user

Below the list, the page shows any Arbiter record with no Philter user, such as a
person from Arbiter's removed local sign-in who has no Philter user with their email
address as username (see
[Upgrading from local sign-in](../security.md#upgrading-from-local-sign-in)). Nobody can sign in as
one, but it still counts as a group member and team lead. **Delete record**
removes it, and removes it from its groups, after Philter confirms there is no
such user. A record that is a group's only member is not deleted until someone
else joins the group. The section appears only when Philter's user list has
loaded.

### API-only users

A user with no password, such as an account for automation that calls Arbiter's
API with a Philter API key (see [API keys](../security.md#api-keys)), is marked
**API only**. It cannot sign in, and here it can only be deactivated
or reactivated, so it is not given a password by accident. Manage it with
Philter's Users API.

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

Arbiter creates no accounts: the first administrator is Philter's `admin` (see
[First sign-in](../security.md#first-sign-in)). On first start with empty `groups` and
the demo loader enabled, Arbiter creates a `Default` group containing every existing
user and assigns the seeded sample-files batch to it. This is placeholder
configuration; add real users and groups before going live.
