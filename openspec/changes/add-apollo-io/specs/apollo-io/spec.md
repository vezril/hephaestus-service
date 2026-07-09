# apollo-io — Spec Delta

## ADDED Requirements

### Requirement: Apollo object-store gRPC client from Lexicon

Hephaestus SHALL talk to Apollo through a gRPC `ObjectApi` client generated from the single-source
Lexicon contract (`io.codex %% lexicon-grpc`, pinned by SemVer), not a repo-local `.proto`. The
service SHALL run on the Pekko version that client requires (1.2.0), and SHALL build the client from
configuration (Apollo host/port, deadline).

#### Scenario: Client built from config
- **Given** an Apollo endpoint configured by host/port (env-overridable)
- **When** the service starts
- **Then** an `ObjectApi` client is constructed from `GrpcClientSettings` and is usable by the pipeline

#### Scenario: Edge case — contract sourced from Lexicon, not vendored
- **Given** the build definition
- **When** the gRPC client is resolved
- **Then** it comes from the pinned `lexicon-grpc` artifact (no local copy of `object_api.proto`)

### Requirement: Stream-read an original with end-to-end md5 verification

The client SHALL read an original via server-streaming `GetObject`, surfacing the `ObjectMetadata`
header and the payload as a backpressured `Source[ByteString]`, and SHALL compute the md5 of the
streamed bytes and verify it equals the metadata header's md5. A mismatch or a truncated stream
SHALL be a **terminal** (non-retriable) failure.

#### Scenario: Original read and verified
- **Given** an object stored in Apollo with md5 `ab34…f`
- **When** the client reads it
- **Then** it yields the metadata header then the bytes, and the computed md5 equals `ab34…f`

#### Scenario: Edge case — md5 mismatch is terminal
- **Given** an object whose streamed bytes do not hash to the header's md5
- **When** the client reads it
- **Then** the read fails with a terminal (non-retriable) error naming the object

#### Scenario: Edge case — truncated stream is terminal
- **Given** a `GetObject` stream that ends before the declared size
- **When** the client reassembles it
- **Then** the read fails terminally (not silently truncated)

### Requirement: Stream-write a derivative, atomic and content-addressed

The client SHALL write a derivative via client-streaming `PutObject` (a `PutHeader` then payload
chunks from a `Source[ByteString]`), returning Apollo's computed md5/size. The object key SHALL be
content-addressed as `derivatives/<md5[0:2]>/<md5>/<name>` (the original's md5), and the write SHALL
be atomic — visible only on successful stream completion — so a redelivered or reprocessed job
overwrites byte-identical content at the same key (idempotent).

#### Scenario: Derivative written at its content-addressed key
- **Given** an original with md5 `ab34…f` and a produced `thumb.webp`
- **When** the client writes it
- **Then** it is stored at `derivatives/ab/ab34…f/thumb.webp` and the response md5/size match the sent bytes

#### Scenario: Edge case — reprocess overwrites identically (idempotent)
- **Given** a derivative already written for an original
- **When** the same derivative is written again
- **Then** the same key is overwritten with byte-identical content and the operation succeeds

#### Scenario: Edge case — a failed write commits nothing
- **Given** a `PutObject` stream that errors mid-transfer
- **When** the stream fails
- **Then** no partial object is visible at the target key (atomic commit-on-completion)

### Requirement: Head/exists check

The client SHALL support a `HeadObject` lookup returning object metadata when present and a typed
not-found when absent, to enable skip-if-present and reprocess-overwrite decisions.

#### Scenario: Present object returns metadata
- **Given** a stored derivative key
- **When** the client heads it
- **Then** it returns the object's metadata (size, md5, generation)

#### Scenario: Edge case — absent object returns typed not-found
- **Given** a key that does not exist
- **When** the client heads it
- **Then** it returns a typed not-found (not an exception leaking to callers)

### Requirement: Transient vs terminal failure classification

The client SHALL surface a typed error that classifies failures as **retriable** (Apollo
unreachable, `UNAVAILABLE`, `DEADLINE_EXCEEDED`, connection reset) or **terminal**
(`NOT_FOUND`, checksum/`FAILED_PRECONDITION`, `INVALID_ARGUMENT`, md5 mismatch), so downstream
result-reporting can leave retriable jobs for redelivery and quarantine terminal ones.

#### Scenario: Apollo unreachable is retriable
- **Given** Apollo is down or times out
- **When** the client performs any operation
- **Then** it returns a typed error flagged `retriable = true` (no crash, no wedged lane)

#### Scenario: Not-found / bad-argument is terminal
- **Given** an operation that returns `NOT_FOUND` or `INVALID_ARGUMENT`
- **When** the client maps the status
- **Then** it returns a typed error flagged `retriable = false`
