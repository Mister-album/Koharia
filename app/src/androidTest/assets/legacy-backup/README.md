# Frozen historical-schema backups

These fake-data fixtures are independently encoded from the tag schemas recorded in
`provenance.json`. They were **not exported by historical APKs** and do not prove an
installed-version/database upgrade. Do not regenerate them with current model serializers.
The instrumentation tests pin their complete SHA-256 hashes.

- `v0.4.0-plain.kohariabk`: format/schema 1, legacy Komga inventory and independent
  settings keys, category, chapter flags/position and history. No EPUB fields.
- `v0.6.0-encrypted.kohariabk`: format/schema 2, generic preference inventory for
  Komga, local folder, LANraragi and Smanga, independent settings, categories and
  reading data. Komga/local records also contain EPUB progress, bookmark and TTS.
  Test password: `fixture-password`. Fixed salt/IV are intentional for this fake fixture;
  encryption follows the tagged PBKDF2-SHA256/AES-256-GCM format and AAD.

Both payloads omit the newer explicit connection inventory. Source marker preferences
are synthetic sentinels; no servers, credentials, files or directory grants are included.
The test creates isolated preference inventories and owned SQL rows, preserves colliding
old owners, confirms the actual shared-settings selection UI, and exports/redecodes the
restored reading rows. It cleans only its own data and retains installed packages.

Protobuf encoding uses the tagged field numbers, varints/length-delimited messages,
little-endian doubles, and the Kotlin sealed preference wrapper (type field 1, value
message field 2). Historical source hashes and exact Git commits are included for review.
Current-only providers and network storage modes are outside these historical cases;
their current-schema isolation coverage remains in `ConnectionSourceRestoreDeviceTest`.
