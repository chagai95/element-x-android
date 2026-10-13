# Publishing the fork on GitHub

The fork is complete and tagged locally, with a full bare mirror beside it. What is missing is a
place to push it to.

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
