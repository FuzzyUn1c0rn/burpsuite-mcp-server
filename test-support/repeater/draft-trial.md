# Disposable Repeater draft identity trial

This test extension has one local action that copies the visible draft from a selected synthetic HTTP/1.1 Repeater tab. The supported source is the exact request below with CRLF line endings and a blank line after the Host header. Its visible target must be `http://127.0.0.1:18180`. No listener is needed. The archive includes the reviewed marker witness, the test entry point and Kotlin's standard library. The regular build and MCP extension are separate.

```http
GET /m0-draft-witness HTTP/1.1
Host: 127.0.0.1:18180

```

## Reproduce the archive offline

Use the exact candidate checkout and Java 21. Build from the repository root.

```powershell
./gradlew.bat --offline --no-configuration-cache -I test-support/repeater/draft-trial.init.gradle m0DraftTrialJar
Get-FileHash -Algorithm SHA256 build/m0-draft-trial/m0-draft-trial.jar
```

Record the Git SHA, Java version, archive hash and entry list. The only project classes should be the witness and its inventory types plus `M0DraftTrialExtension` and `DraftTrial` and their generated classes. This check and the witness component tests run offline. They establish the build and matching logic.

## Verification after review and isolation release

Use a disposable project with an already selected flat Repeater pane. Create the exact synthetic source as an unsent draft and record its editor text, target and empty response pane. In a fresh trial, also create an unrelated draft with the same display name as the source. Select the source and choose **Clone selected synthetic M0 draft (local only)** from its Repeater context menu. The action rechecks the source header, content, editor text and target before copying the sampled text into one new draft through Montoya. The clone receives a generated marker caption.

For a positive `CLONE_CONFIRMED` result, check that `firstMarkerCapture` and `secondMarkerCapture` are consecutive and distinct. Verify the exact visible editor text and target on the marked clone, then inspect the original and the unrelated tab. They should still show their own drafts. All response panes should remain empty.

In separate fresh trials, try to interleave an unrelated tab after creation starts, change source selection or text between opening the menu and choosing the action, or cancel observation if the timer remains active. An unrelated tab with a duplicate display name must never be reported as the clone. A stale source must prevent creation. A duplicate or replaced marker, or a marked draft that cannot be read in the current view, must refuse confirmation. Choose **Cancel draft identity observation** while the timer is active or unload the test extension. Cancellation stops observation and may leave a draft that was already created. The action is consumed until the extension reloads. The harness does not retry, rename, or remove candidates.

The output contains a generated caption, counts and comparison results. `baselineIdentitiesPreserved` and the readback describe discrete Swing observations. They establish neither continuous ownership nor effective execution settings or exact model bytes. Unsupported layouts and unavailable, ambiguous, or changed readback report `UNCONFIRMED`. Production naming, native rename and MCP registration remain outside this trial.
