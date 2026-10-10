# Releasing to Maven Central

Coordinates: `io.github.yavuzhankursun:tenant-guard`. The `io.github.<user>` namespace is verified
by signing in to the Central Portal with the GitHub account; no domain is needed.

## One-time setup

1. **Central Portal account.** Open <https://central.sonatype.com>, choose *Sign in*, then
   *Continue with GitHub* as `yavuzhankursun`. Under *Namespaces*, `io.github.yavuzhankursun` must
   show *Verified*.
2. **User token.** Account menu → *View Account* → *Generate User Token*. Copy the username and
   password now; they are shown once. They become `CENTRAL_TOKEN_USERNAME` and
   `CENTRAL_TOKEN_PASSWORD`.
3. **GPG key.**
   ```bash
   gpg --full-generate-key                       # ed25519 or RSA 4096, a strong passphrase, expiry e.g. 2y
   gpg --list-secret-keys --keyid-format long    # note the fingerprint
   gpg --keyserver hkps://keyserver.ubuntu.com --send-keys <FINGERPRINT>
   ```
   Check the key appears on <https://keyserver.ubuntu.com>, and back up the revocation certificate
   from `~/.gnupg/openpgp-revocs.d/`.
4. **Repository secrets** (stdin keeps them out of shell history):
   ```bash
   gpg --armor --export-secret-keys <FINGERPRINT> | gh secret set GPG_PRIVATE_KEY
   gh secret set GPG_PASSPHRASE
   gh secret set CENTRAL_TOKEN_USERNAME
   gh secret set CENTRAL_TOKEN_PASSWORD
   ```
5. **Private vulnerability reporting.** Settings → Code security → enable *Private vulnerability
   reporting* (linked from `SECURITY.md`).

## Each release

1. **Dry run.** Actions → *Release* → *Run workflow* on `main` with `dry_run` checked. It checks
   the secrets, runs the tests and signs the artifacts; nothing is uploaded.
2. **Version and changelog.**
   ```bash
   mvn versions:set -DnewVersion=0.2.0 -DgenerateBackupPoms=false
   ```
   In `CHANGELOG.md`, rename `## [Unreleased]` to `## [0.2.0] - YYYY-MM-DD` and add a new empty
   Unreleased section.
3. **Tag.**
   ```bash
   git commit -am "release: 0.2.0"
   git tag -a v0.2.0 -m "v0.2.0"
   git push origin main v0.2.0
   ```
   The workflow refuses a SNAPSHOT version or a tag that does not match the pom version.
4. **Publish.** The workflow uploads with `autoPublish=false`: open
   <https://central.sonatype.com/publishing/deployments>, review the VALIDATED deployment and press
   *Publish*. It appears on Maven Central after some tens of minutes. Then publish the draft GitHub
   release the workflow created.
5. **Next cycle.** `mvn versions:set -DnewVersion=0.3.0-SNAPSHOT -DgenerateBackupPoms=false`.

## Local checks

```bash
mvn -Prelease -Dgpg.skip -DskipTests package   # main, sources and javadoc jars without signing
```
