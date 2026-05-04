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
