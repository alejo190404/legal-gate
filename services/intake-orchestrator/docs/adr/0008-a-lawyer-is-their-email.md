# ADR-0008: A lawyer is their email within the firm

- Status: Accepted
- Date: 2026-10-21

## Context

A lawyer who leaves the firm becomes a Former Lawyer: kept, never removed, because the Clients and
Activities they worked on still name them. Lawyer emails are unique per firm. Saving the team with a
new lawyer whose email a Former Lawyer has used to delete the Former Lawyer, which failed on the
foreign key from their finished work (#78).

## Decision

A lawyer is identified by their email within the firm. A lawyer saved under a new id with the email
of a stored lawyer who is not otherwise on the team is that lawyer returning: they keep their id,
become active again, and take the submitted name, meeting URL, duration and availability. One
person who leaves and returns any number of times is one record.

A settings save never deletes a lawyer. It refuses two lawyers with one email
(`duplicate_lawyer_email`) and a lawyer edited to the email of a different stored lawyer
(`lawyer_email_taken`, naming them). Removing a lawyer still in charge of Clients or open
Activities and adding them back in one save is refused like any other removal: to change their
email, edit them.

## Considered Options

- **Refuse re-adding a Former Lawyer's email** and add a way to restore them from Equipo. Rejected:
  it needs a restore screen, and a returning lawyer has no other way back.
- **Email as a mailbox, not a person** (a role address like `litigios@` passed from one lawyer to the
  next). Rejected: reactivating would rename every Activity the previous holder did. If firms need
  shared role mailboxes, that is a separate concept, not a lawyer.

## Consequences

- A lawyer submitted as inactive under a Former Lawyer's email stays a Former Lawyer. The console
  never sends that; it was left as is.
- Two lawyers may trade emails, or one may take an email another gives up, in a single save.
