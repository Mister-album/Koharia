# Frozen v0.7.0 network-storage schema

The SQLite fixture uses the complete `v0.7.0` tagged `.sq` schema (version 25),
with SQLDelight imports/type adapters removed from its DDL. It is not produced by
reverse-editing the current schema and is not a database copied from an old APK.
`provenance.json` records the commit, source hashes and fixture hashes.

The fake data follows the tagged `StorageProgressRecord`/`StoragePendingProgress`
shape, including the former installation-wide writer and no newer confirmation field.
WebDAV and SMB each have a primary owner plus foreign-account and foreign-root controls.
All endpoints use loopback and no credentials other than a fake username are present.

`Release070StorageUpgradeDeviceTest` copies the frozen database to a unique name,
runs the production schema bridge and SQLDelight migration, reads old preferences
in isolated stores, and exercises the actual SQL repository/StorageProgress. Every
backend I/O method fails the test. All old pending/counter/catalogue rows must survive;
new writer IDs must be independent by owner and persist after database reopen.

The test removes only its own database/preferences. It does not change the installed
fixture database or claim a second historical APK upgrade, real network access,
SAF grants, or coverage of every v0.7.0 provider state.
