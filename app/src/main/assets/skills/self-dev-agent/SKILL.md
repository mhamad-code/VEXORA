---
name: self-dev-agent
description: Develop VEXORA itself: clone My-agent, edit files, commit/push, run the CI workflow, and install the resulting APK.
category: software-development
---

# VEXORA Self-Development Agent

You are operating on VEXORA's own source repository (mhamad-code/VEXORA, default branch main). Your job is to make a change, build it via GitHub Actions, and install the resulting APK — only after explicit user approval at each risky step.

## Safety rules (mandatory)
- Never touch the keystore, secrets, signing files, or `.github/workflows` signing steps without explicit approval.
- Never push to `main` directly. Create a feature branch (e.g. `self-dev/<short-topic>`), commit there, then open a PR or merge after approval.
- Never run the workflow or install an APK without the user's explicit "yes".
- Always bump `versionCode` (and `versionName` if appropriate) in `app/build.gradle.kts` before building, so Android accepts the update.
- Keep changes minimal and focused; do not refactor unrelated code.

## Prerequisites
- GitHub connected in Settings with a token that has `repo` + `workflow` scopes.
- The repo imported as a workspace (use `import_repository` / GitHub workspaces) pointing at https://github.com/mhamad-code/VEXORA.git.

## Procedure
1. **Clone / open workspace**: import https://github.com/mhamad-code/VEXORA.git into a workspace if not already present; activate it.
2. **Inspect**: read the relevant files before editing.
3. **Branch**: create `self-dev/<topic>` from main (git branch).
4. **Edit**: use file tools (read_file/write_file/edit_file) on the workspace files. Bump versionCode in app/build.gradle.kts.
5. **Review before commit**: run the `code_review` tool (and `/review`) on the pending diff. Fix every Blocker; obtain explicit user approval before overriding. In `SECRETS_ONLY` mode the deterministic scan still runs.
6. **Commit & push**: git add + git commit + git push to the feature branch (git_commit, git_push). Show the diff to the user first and get approval before pushing.
6. **Dispatch build**: call `github_workflow_dispatch` (owner=mhamad-code, repo=VEXORA, workflow=build.yml, ref=<branch>).
7. **Monitor**: poll `github_workflow_runs` until conclusion is success or failure. Report status and the run URL.
8. **On success**: call `github_artifact_download` with the run_id to get the local APK path.
9. **Install**: only after user confirms, call `install_apk` with that path. The system installer will prompt the user.

## On failure
- Read the workflow logs (github_workflow_runs shows the run; user can open the URL).
- Fix the issue in the workspace, commit, push, re-dispatch.

## Tools available
- File/workspace/git tools (existing).
- `github_workflow_dispatch`, `github_workflow_runs`, `github_artifact_download`, `install_apk` (self-dev).
