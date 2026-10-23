# Local HTTPS fixture

`localhost-fixture.p12` is a synthetic, test-only PKCS12 identity for the loopback
server in `ConnectionVerificationThreadDeviceTest`. Alias: `fixture`; password:
`fixture-password`. It contains no production credential and must never be used
outside tests. Its certificate covers localhost and 127.0.0.1 (2020–2049).

Only the test's private OkHttp client trusts it. No device trust store is changed.
