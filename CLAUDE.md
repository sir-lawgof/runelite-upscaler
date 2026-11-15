@AGENTS.md

# runelite-upscaler

Read docs/REQUIREMENTS.md before doing any work. It's the source of truth.

Rules:
- Work on ONE step at a time, in order (Step 1, 2, 3). Don't start a step until every MUST requirement of the previous step passes its acceptance check.
- Cite requirement IDs (G-, A-, S1-, S2-, S3-, T-, D-, Q-) in commit messages, code comments and test names.
- Items marked VERIFY are unconfirmed assumptions. Check the real source or SDK docs first, then log the result in REQUIREMENTS.md section 8.4 (Findings log).
- If code and the spec disagree, stop and ask. Record decisions in section 8.1.
- Never commit DLLs, native binaries, credentials or RuneLite session files.
- Forked upstream GPU plugin code lives in com.runeliteupscaler.renderer. Keep its license headers. Upstream version is in docs/UPSTREAM.md.
- Build: gradlew.bat build. Run: the test launcher in src/test (developer mode).
