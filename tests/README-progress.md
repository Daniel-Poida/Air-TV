# Air TV 0.1.4: progress and recovery checks

TransferProgressTest.java is deterministic and independent of Android. Compile it with app/src/main/java/io/github/jqssun/airplay/files/TransferProgress.java and run with java -ea.

Hardware scripts were run explicitly against the authorized idle TV 192.168.0.31. They generate only AirTV-progress-test-* fixtures, verify saved SHA256 values, and delete their own fixtures through the TV confirmation UI. They require adapting the project-specific ADB wrapper and toolchain paths and a receiver with PIN disabled (or adapting authentication to the selected TV PIN). They do not change the TV PIN setting. Do not run on a TV currently streaming.

The signed test-only instrumentation in recovery/ targets dev.airtv.receiver. It verifies disconnect cleanup, engine recovery after an injected native callback, cancellation of an old pending recovery by a new session, and Wi-Fi-lock release. Mode transfer waits for the scoped synthetic upload and injects a reset while bytes are being streamed, then waits for successful commit. Build with the same signing key as the receiver. No test endpoint is added to the production app. Remove dev.airtv.tests after running it.

These are callback-recovery tests, not a substitute for a 30–60 minute real Mac AirPlay mirroring soak test. Actual Android process recreation, real router/network switching, and native Finder AirDrop are not claimed as tested by these scripts.
