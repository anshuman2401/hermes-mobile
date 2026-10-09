## Change

Describe the bounded behavior or documentation change and link the related issue or decision, if any.

## Verification

Follow [CONTRIBUTING.md](https://github.com/anshuman2401/hermes-mobile/blob/main/CONTRIBUTING.md) and record local commands and their results. This repository has no GitHub Actions verification workflow.

- Documentation-only: content and link review; `git diff --check` result.
- Executable changes: focused checks, `./scripts/verify.sh`, and the clean committed `./scripts/verify.sh --final-candidate` result. Include test counts, failures/skips, lint summary, and safe candidate/APK provenance (HEAD, hash, size, package, variant, version); omit the local APK path.
- State any missing prerequisite or unrun device/emulator check. Device and host behavior need separate evidence.

Use synthetic examples. Do not paste credentials, private hosts or paths, session IDs, prompts, responses, or unredacted logs/screenshots.
