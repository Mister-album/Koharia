# Koharia contributor list

`update.py` updates the marked blocks in both root README files. It walks non-merge commits reachable from the checked-out HEAD, excluding:

- History through `82338d7d64725d941f2edb8bc5e4988155869a48`, the parent of Koharia's first development commit (`472f9d09c`).
- Commits reachable from the supplied Mihon upstream reference.
- GitHub bot accounts and authors with `[bot]` in their identity.

GitHub's commit API resolves commit authors to account avatars; multiple email identities linked to the same account are deduplicated. Unlinked authors appear as plain names without email addresses. This lists commit authors, not merge committers or `Co-authored-by` trailers. An upstream contributor who authors a new Koharia commit can appear.

Run from a full checkout with Python 3.10+ and an authenticated GitHub CLI:

```powershell
git fetch --no-tags https://github.com/mihonapp/mihon.git main:refs/remotes/contributors-upstream/main
python tools/contributors/update.py --upstream-ref refs/remotes/contributors-upstream/main
```

The `Update contributors` workflow runs on pushes to `main` and supports manual dispatch. It commits the two README files and generated circular avatar SVGs under `.github/assets/contributors/` when they change. Avatars embed the original GitHub image and use an SVG circle clip, so the shape does not depend on README CSS. Existing avatars are reused; remove a generated avatar file and rerun to refresh that image. It uses the repository's `GITHUB_TOKEN`; branch rules must permit its documentation commit. Pushes remain non-forced: a concurrent main update can reject the push, in which case rerun the workflow. API failures stop generation before either README is written.
