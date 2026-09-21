# ADR-0007: SMB directory browsing and selected track descriptions

- Date: 2026-09-21
- Status: Accepted; phase 1 and phase 2 focused device gates passed
- Scope: Browse, direct playback, ordinary playlists, and explicit selected-folder library membership.

## Decision

SMB connections no longer participate in normal manual or automatic catalog synchronization.
Existing catalogs remain available. Opening a connection lists its configured root; opening a
folder lists that folder only. Connection testing opens a directory handle without enumeration.
Anonymous authentication and account authentication with an empty password are supported.

The browser uses SMBJ's directory iterator, never opens audio, artwork or lyric payloads while
listing, and never publishes a directory as a source catalog. Rows initially use filenames.
Single-song playback, current-directory playback, explicit selection and ordinary playlist
addition use stable source/path IDs. Changing a connection's endpoint requires a new connection
so old playlist IDs cannot silently resolve against a different server.

Room schema 33 adds `remote_selected_tracks`. These records are descriptions, not catalog
membership. They survive catalog replacement and source deletion so playlists retain known
titles while offline. Credentials and temporary authenticated URLs are never stored here.
Queue restoration resolves these records locally; playback uses the existing SMB transport.
Missing remote IDs are shown as unavailable rather than guessed by filename during import.

Optional metadata follows the process playback owner and probes only the current selected
song. Metadata, SMB artwork and SMB lyrics share one optional-I/O mutex; audio does not take
that mutex. New optional reads are rejected while that source is preparing/buffering. An
already-blocking network read may finish before cancellation is observed. No audio conversion,
DSP or output-quality setting is changed.

Phase 2 adds an explicit “add current folder to library” action. Room schema 34 stores managed
folder scopes separately from their track membership. A scope records its relative directory,
whether child folders are included, observed source revision and last completed discovery.
Overlapping scopes may reference the same `remote_tracks` row; removing one scope only removes
tracks that have no remaining scope membership. Existing pre-phase-2 SMB catalog rows are
seeded into an immutable `LEGACY` scope so the first managed folder cannot reinterpret old
catalog data as deletion evidence.

Folder discovery remains browse-first: it streams directory entries and does not open audio,
artwork or lyric payloads. Recursion is opt-in and remains under the selected subtree. A
partial, timed-out or otherwise incomplete traversal never replaces existing membership.
Publication and removal both commit through `RemoteCatalogRepository`, rechecking source
operation generation and configuration at the transaction boundary. Selected-track records
remain independent, so ordinary playlists and queue recovery survive managed-scope removal.

SMBJ 0.15.0 has an upstream anonymous SMB3 null-session-key failure. Mica's passwordless
“anonymous access” uses SMBJ's guest authentication context instead of its crashing anonymous
context; credentialed authentication is unchanged. This preserves guest-share behavior without
downgrading dialects or disabling negotiated signing.

## Ownership and side-effect protocol

| Work | Validity owner and waits | Side effects and serialization | Deterministic coverage |
|---|---|---|---|
| Directory navigation | `SmbBrowseOwner` request counter plus source operation token; credentials, session open, directory iterator, IO return | UI state publication passes the repository gate and then the browse gate; close/new load invalidate the request | `SmbBrowserTest`: non-cancellable old directory returns after newer directory; close prevents publication |
| Selected descriptions | Source token rechecked after Room queries and before each batch | Repository mutex, non-cancellable Room transaction; no network inside | `SmbSelectedTracksTest`: stale selection and replacement cannot write; catalog replacement preserves selected records |
| Playback handoff | Source token and current browse snapshot rechecked after description registration | Repository publication gate encloses the synchronous queue callback | `SmbSelectedTracksTest`: disable source after registration, release old continuation, assert no playback publication |
| Playlist addition | Captured playlist revision and source token | Playlist mutation mutex -> remote repository mutex -> one nested Room transaction; playlist and descriptions commit or roll back together | `SmbPlaylistFlowTest`: deletion rejects captured selection, SQLite-triggered failure rolls back both, cold 10k-ID restoration |
| Current-song metadata | Source token, metadata request counter and content revision; after credentials/open/probe/Room lookup | Repository mutex and Room transaction; player checks current ID before in-place queue metadata refresh | `SmbNowPlayingMetadataTest` and `SmbSelectedTracksTest`: release old probe after source/content changes and assert no stale stored metadata/artwork |
| Artwork and lyrics | Existing source validity and exact-resource authorization; SMB current-song and buffering policy | Existing artwork/cache owners retained; selected records extend exact artwork authorization | Existing remote transport/provider tests plus stale selected artwork assertions; actual network cancellation remains a device gate |

## Capacity and deliberate limits

The baseline is 10,000 songs, each with complete word-timed lyrics, on an 8 GB Android phone.
Browsing ignores lyric payloads entirely. Automated fixtures enumerate 10k songs and matching
lyric entries without payload opens; another fixture persists/restores 10k playlist references.
They do not prove total process memory or phone stability.

Only the visible directory is retained. Enumeration stops at 20,000 retained entries (including
candidate images), 100,000 visited entries, a conservative 32 MiB descriptor allowance, or a
30-second monotonic budget checked between returned entries. A blocked SMB call still uses
the transport timeout. Sorting is in-place; same-stem artwork matching uses a lookup map.
Only 32 directory scroll positions are kept. Partial results are labelled and cannot be used
for whole-directory playback or select-all.

The 32 MiB allowance is a descriptor estimate, not a measured JVM/Android heap ceiling. There
are additional lists, maps, queue projections and database objects. Selected descriptions
remain durable and their observation currently loads all descriptions, so accumulated history
beyond the 10k baseline needs separate measurement/retention work. Lyrics are not loaded by
these flows. An 8 GB physical-device capacity PASS is explicitly not claimed.

Compared with the initial design draft, phase 1 uses short-lived directory sessions and a
bounded in-memory current directory, rather than a reusable session pool or temporary paged
directory database. This keeps lifetime and publication ownership small. Very large single
directories are partial; navigation to smaller subdirectories is the supported fallback.

## Acceptance evidence and remaining capacity work

Phase 1 implementation verification on 2026-09-21 covered 152 targeted JVM/Robolectric tests
plus AndroidTest compilation, Lint and Debug build. Its isolated real-SMB gate subsequently
passed directory browse, WAV/FLAC playback and transition, seek, pause/resume, ordinary
playlist persistence, SMB3 account login, cold queue/playlist recovery, offline retention and
reconnect.

Phase 2 adds deterministic coverage for non-recursive and recursive folder scopes, overlapping
membership, selected-track recovery after scope removal, and the rule that incomplete
discovery cannot delete previously published membership. The v32→v34 migration is validated
through Room and seeds historical SMB catalog membership into `LEGACY`. Compose coverage
exercises add-current-folder, explicit child-folder inclusion, refresh and removal.

On 2026-09-22 a focused physical-device SMB3 gate used the synthetic `Album` share and passed:
adding `Album` published exactly its two audio files, refreshing kept two members, removing
the managed scope removed its catalog membership, and `lastSyncAtMs` remained untouched.
During this gate SMBJ 0.15.0 reproduced its upstream anonymous-authentication null-session-key
bug; switching passwordless guest shares to `AuthenticationContext.guest()` removed the crash,
and the same focused device test then completed successfully.

The remaining work is capacity/soak rather than a correctness prerequisite for phase 2:
measure 10k startup/playlist loading, very large single-directory behavior and peak process
memory on the 8 GB target device/router class. Targeted regression evidence is still distinct
from claiming that the entire `micaCheck` screenshot matrix or the full 10k hardware capacity
suite has run.
