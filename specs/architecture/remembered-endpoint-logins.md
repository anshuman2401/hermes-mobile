# Remembered endpoint logins

Status: implementation contract; automated checks use synthetic endpoints and credentials. JVM checks and APK assembly do not certify Android Keystore, a physical device, or a deployed host.

## User behavior

The password form starts with Remember me checked for an endpoint without an existing preference. A successful checked login remembers one account for that endpoint. A successful unchecked login forgets its saved username/password; the authenticated cookie still works. Failed manual authentication does not replace the saved account or remember choice. A later successful manual login may replace the saved account.

Cold startup restores the most recently successful connection and port. Entering a known hostname fills its most recently successful port. Explicit port edits take priority over asynchronous lookup; unknown hosts retain the default port of 9119. Connect waits for the pending lookup before validating its endpoint. A host edit cannot publish an older lookup or authentication result.

Changing hosts revokes the previous profile's session/cache authority without suppressing its remembered automatic login. Sign-out keeps remembered credentials encrypted but revokes the profile's network and local cache authority. Its durable suppression survives process restart. Automatic use also honors the profile's durable SIGNED_OUT tombstone, so an interrupted or failed suppression write cannot undo an already-persisted sign-out. Startup presents the prefilled Connect form when remembered credentials remain; only an explicit Connect or profile switch permits their use again. Successful authentication re-enables automatic recovery. Forget saved login removes the endpoint's secret and remember choice, leaves last-used preferences, and does not end an existing authenticated session.

## Ownership and security

Compose emits checkbox/forget/connect intents to the ViewModel, which uses AuthRepository and the existing recovery coordinator. UI state never receives remembered passwords. Cookie/session state and local cache access remain profile-scoped; sharing a saved login across profiles does not share cache authority.

The record lookup uses canonical hostname and effective port. Scheme is retained in storage identity and authenticated associated data so HTTP and HTTPS cannot share credentials. Root origins only are admitted. IPv6 hostnames use bracketed URL serialization. The hostname/port is a lookup identifier, not encryption key material.

Endpoint records and last-used preferences use the existing Android Keystore-backed AES-256-GCM engine, app-private durable atomic files, opaque hashed credential filenames, and disabled Android backup. Endpoint record AAD binds its type, schema version and canonical origin; the decrypted identity must agree. Preferences bind their own record type and schema. Read/decryption/schema failure fails closed; unreadable endpoint records are retained so absence cannot resurrect a legacy password. No software-key fallback, scheme change, trust bypass, redirect following, telemetry, or new host endpoint is introduced.

Password login still performs status preflight and accepts only a host-advertised provider and valid origin-bound cookie. Recovery tries the cookie first and allows one saved-password attempt per cycle. Explicit and background requests do not coalesce a failed result across their differing authorization. Definite password rejection removes the usable saved password until manual correction; network failures retain it. Mutable password buffers and new record plaintext byte buffers are wiped after use.

## Persistence and migration

Schema-1 endpoint records contain encrypted origin, username, provider, password, remember choice, and automatic-use eligibility. Schema-1 preferences contain successful origins in most-recent-first order, updated after gateway readiness. Remembered passwords are separate from the existing profile-local login/cache authority record.

Legacy eligible passwords migrate under the vault mutex. Existing endpoint records, including forget/suppression tombstones, win. For duplicate legacy records, newest profile updatedAt wins, with ascending local profile ID as the stable tie-break; an ineligible or password-less winner cannot revive another profile's password. Destination writes complete before legacy password fields are cleared. Profile-local account metadata and cache epochs remain intact. Signed-out records and cookie-only installs gain no invented password. Repeating migration is safe after restart or partial failure.

For endpoint selection, the active matching profile wins, otherwise newest updatedAt wins with the same profile-ID tie-break. Connecting to another endpoint creates its own local profile instead of overwriting the previous host; added profiles own authentication before borrowing an endpoint password. A failed profile preparation returns to editable setup before credential exposure. Failed encrypted-preference reads during Connect or profile switching show a fixed generic error and return to editable setup; Connect issues no network request when preference preparation fails. Connection readiness is rechecked after preference persistence before announcing success. Sign-out, profile switching, and startup reload cancel queued Connect requests and preference reads. Deleting/clearing the final profile for an endpoint forgets its saved login and removes it from successful preferences. Cache-only reset retains credentials/preferences; full local reset removes them. Sign-out, forget and profile changes invalidate outstanding login owners. Conditional authentication publication still fails closed on persistence failures or stale owners, and account changes establish their own cache ownership.

No prompt submission, automatic prompt resend, remote session deletion, or host infrastructure mutation is part of these operations.
