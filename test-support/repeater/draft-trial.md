# Disposable Repeater draft identity trial

This test extension has a single local action that opens a fixed synthetic HTTP/1.1 draft in Repeater. Its sole destination is the reserved loopback fixture address at `127.0.0.1:18180`. No listener is needed. The archive includes the reviewed marker witness, the test entry point and Kotlin's standard library. The regular build and MCP extension are separate.

## Reproduce the archive offline

Use the exact candidate checkout and Java 21. Build from the repository root.

```powershell
./gradlew.bat --offline --no-configuration-cache -I test-support/repeater/draft-trial.init.gradle m0DraftTrialJar
Get-FileHash -Algorithm SHA256 build/m0-draft-trial/m0-draft-trial.jar
```

Record the Git SHA, Java version, archive hash and entry list. The only project classes should be the witness and its inventory types plus `M0DraftTrialExtension` and `DraftTrial` and their generated classes. This check and the witness component tests run offline. They establish the build and matching logic.

## Verification after review and isolation release

Use a disposable project with an already selected flat Repeater pane and at least one unrelated synthetic request tab. The verifier should record the existing draft and select **Create one synthetic M0 draft (local only)** once from a Repeater context menu. The action creates one request through the official draft-only API with a generated temporary caption. Keep the original tab and the pane in place while the witness samples them. A verifier can open the context menu and choose **Cancel draft identity observation** while the timer is active, or unload the test extension. An unconfirmed creation may leave the synthetic draft visible.

In extension output, compare `firstMarkerCapture` and `secondMarkerCapture`. They must be consecutive and distinct. A positive `baselineIdentitiesPreserved` means the unrelated header and content identities were present in each observed inventory. These are discrete EDT observations. They cannot prove that tab identities or content stayed unchanged between captures.

`editorHashEqual` compares the full text of a uniquely identified visible editor to the fixed draft text, with no normalization. `destinationEqual` compares the visible target label. Unsupported layout or unavailable editor evidence may produce `unknown`. A line-ending conversion can return `false`. These comparisons are optional evidence about the visible editor. Neither exact model bytes nor effective settings are established by this trial. The archive emits only the generated caption, counts, fixed labels and equality results.

A gap, duplicate or lost/replaced marker, timeout, cancellation or inventory failure returns `UNCONFIRMED`. The action is consumed until the extension is reloaded. Final naming, native rename, cloning and production routing require separate evidence.
