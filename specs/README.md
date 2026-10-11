# Documentation guide

Start with the [contributor rules](../AGENTS.md), [current architecture](architecture/current-system.md), [verification runbook](testing/verification.md), and [QA matrix](testing/android-qa-matrix.md). The [toolchain reference](build/toolchain.md) lists build prerequisites.

This repository contains the Android source and a small contributor reading set. The private development repository holds dated plans, decisions, reviews, build records, screenshots, and task evidence. Keep current architecture, feature contracts, toolchain guidance, verification instructions, and QA coverage in Git. Before archiving a checkpoint, move any unique lasting protocol, security, persistence, ownership, retry, or migration decisions into the current reference documents and update incoming links. Keep local checkpoints and generated evidence under ignored `build/` directories; publish only concise, redacted verification summaries in the relevant pull request. Ignore rules do not remove files already tracked by Git.

Source, tests, and current verifier reports establish implemented behavior; documentation alone does not certify a device, host, or release.
