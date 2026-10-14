# Publishing the fork on GitHub

**Done — the fork is published at <https://github.com/chagai95/element-x-android>.**

`chagai/main` is the repository's default branch and every `v*-chagai` tag is there, so the
AGPL-3.0 source-availability promise the About screen makes is actually kept: whatever is
installed from https://chagai.website/matrix-chat/ can be read there.

## How a release reaches it

`./release.sh` pushes automatically, at the very end, after the APK is built, verified and
published. Two details that are easy to get wrong:

- **The push comes from the bare mirror**, `~/android-build/element-x-android.git`, not from the
  working copy. The working copy is a **blobless partial clone** and `git push` refuses from one:
  pack-objects will not lazily fetch the blobs it would have to walk. The mirror is complete, so
  it can push, and it only ever sends the fork's own new objects.
- **Remote names.** In the mirror, `fork` is `chagai95/element-x-android` and `origin` is still
  **upstream** — `origin` is never pushed to. In the working copy, `origin` is the fork (for
  `git ls-remote` and manual work) and `upstream` is element-hq. Nothing is ever force-pushed.

Both repositories carry the key in `core.sshCommand`:

```bash
git config core.sshCommand "ssh -i ~/.ssh/id_ed25519_elementx_rw -o IdentitiesOnly=yes"
```

It is a **deploy key with write access on that one repository** — it cannot touch any other repo,
which is exactly the blast radius wanted here.

To push by hand:

```bash
git -C ~/android-build/element-x-android.git push fork chagai/main "refs/tags/v26.09.4-chagai.9"
```

Upstream keeps its screenshot baselines in Git LFS, which the blobless clone never pulled; nothing
the fork changes is LFS-tracked, hence `lfs.allowincompletepush=true`.

---

## Historical: why it was not pushed until 2026-10-14

## Why it is not pushed yet

Nothing on this server can create or write `chagai95/element-x-android`:

- there is no `gh` CLI installed;
- every key in `~/.ssh` is a **per-repository deploy key** (`Samirmes/INNR`, `chagai95/cinny`,
  `gaumuel/knowledge-system`, `gaumuel/blindbroker`, `gaumuel/innr-fe`) — none is a user key, so
  none can create a repository or push to a different one;
- `https://api.github.com/repos/chagai95/element-x-android` returns **404**: the repository does
  not exist.

## Why it matters

Element X Android is **AGPL-3.0**. This build's About screen already offers its source at
`https://github.com/chagai95/element-x-android`, which is how the licence's source-availability
requirement is met. For a build only Chagai installs that is moot. The moment the APK goes to
anyone else, the repository has to exist and carry this source.

## What Chagai needs to do (one of these)

1. **Create an empty repository** `chagai95/element-x-android` on GitHub (no README, no licence —
   the fork brings its own), then on this box:

   ```bash
   cd ~/android-build/element-x-android
   git remote add fork git@github.com:chagai95/element-x-android.git
   git push fork chagai/main --tags
   ```

   If that key cannot write it, a deploy key with write access on that one repository is enough.

2. **Or** fork `element-hq/element-x-android` through the GitHub UI (which also shares upstream's
   Git-LFS storage, see below) and push `chagai/main` into it.

3. **Or** provide a personal access token with `repo` scope, and the push can be done from here.

## The local mirror in the meantime

`~/android-build/element-x-android.git` is a **complete bare clone of upstream plus the fork's
branch and tags** — `git clone ~/android-build/element-x-android.git` gives a working copy of
`chagai/main`. `release.sh` keeps it in step after every release.

Note the direction in `release.sh`: the mirror **fetches** from the working repo rather than the
working repo pushing to it. The working copy is a partial (blobless) clone, so `git push` refuses
— pack-objects will not lazily fetch the blobs it would have to walk. A fetch lets the mirror
declare what it already has, so only the fork's own new objects move.

## Git LFS

Upstream keeps its screenshot snapshots (`**/snapshots/**/*.png`) in Git LFS. The blobless clone
never pulled those objects, so a push would try to upload LFS objects it does not have; this repo
therefore sets `lfs.allowincompletepush=true`. Nothing the fork changes is LFS-tracked, and the
affected files are only CI screenshot baselines. A GitHub *fork* shares upstream's LFS storage, so
route 2 above sidesteps this entirely; otherwise run `git lfs fetch --all upstream` first if the
baselines are wanted.
