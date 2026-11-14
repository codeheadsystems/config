# Releasing

Every Java module that applies `buildlogic.publish-conventions` is published to Maven Central
(Central Portal) under `com.codeheadsystems`, in one deployment, at one shared version. Today that
is `config-bom`, `config-model`, `config-datastore`, `config-datastore-s3`,
`config-datastore-testkit` and `config-library`; `./gradlew verifyPublishConfig` lists them. A
module joins by applying the plugin and adding a constraint to `config-bom`.

Nothing has been released yet. The workflows below only run when a tag is pushed or a release is
started by hand, so nothing is published until the first version is ready.

## How the version is chosen

- `gradle.properties` holds the **next** version as a SNAPSHOT (`0.1.0-SNAPSHOT`).
- A `vX.Y.Z` or `vX.Y.Z-suffix` tag on `HEAD` overrides it (`settings.gradle.kts`), so a tagged
  build is `X.Y.Z`. Without a tag, builds stay SNAPSHOT and are never signed.

## One-time setup

### 1. Central Portal

1. Sign in at https://central.sonatype.com. The `com.codeheadsystems` namespace is already
   verified (hofmann-elimination publishes under it), so the same account works.
2. Account page, **Generate User Token**. Save the token's username and password. They are not
   your login credentials.

### 2. GPG key

The hofmann-elimination key can be reused. To make a new one:

```bash
gpg --gen-key                                    # RSA, 4096 bits
gpg --list-secret-keys --keyid-format=long       # sec rsa4096/ABCD1234EFGH5678
gpg --export-secret-keys ABCD1234EFGH5678 | base64 -w 0 > private-key.txt
gpg --keyserver keyserver.ubuntu.com --send-keys ABCD1234EFGH5678
gpg --keyserver keys.openpgp.org --send-keys ABCD1234EFGH5678
```

### 3. GitHub secrets

At https://github.com/codeheadsystems/config/settings/secrets/actions:

| Secret | Value |
|--------|-------|
| `CENTRAL_PORTAL_USERNAME` | User token username |
| `CENTRAL_PORTAL_PASSWORD` | User token password |
| `GPG_PRIVATE_KEY` | Contents of `private-key.txt` (base64) |
| `GPG_PASSPHRASE` | The key's passphrase |
| `GPG_KEY_ID` | The key ID, such as `EFGH5678` |

Delete `private-key.txt` once it is uploaded.

## Releasing

Releases are published only from commits on `main`; `publish.yml` refuses anything else.

### By tag

```bash
git checkout main && git pull
./gradlew clean build
git tag -a v0.1.0 -m "Release 0.1.0"     # or v0.1.0-rc.1 for a pre-release
git push origin v0.1.0
```

`release.yml` runs on the tag.

### From the Actions tab

Run **manual release**. It tags the head of `main` with the next patch version (the latest
`vX.Y.Z` plus one; for the first release, `gradle.properties`' version without `-SNAPSHOT`), then
publishes it.

### What the release does

Both paths run `.github/workflows/publish.yml`:

1. Checks the tag format and that the tagged commit is on `main`.
2. Checks that Gradle's version equals the tag's.
3. `./gradlew clean build` (all tests, including the RustFS ones in Docker).
4. Imports the GPG key and runs `./gradlew publishAggregationToCentralPortal`, which signs and
   uploads every module as one deployment.
5. Removes the signing material from the runner.
6. Creates the GitHub release with the module list and jars. A tag with a suffix is marked as a
   pre-release.

Artifacts reach Maven Central in 15 to 120 minutes. Check
https://repo1.maven.org/maven2/com/codeheadsystems/config-bom/ and the **Deployments** page at
https://central.sonatype.com.

After a release, raise `version` in `gradle.properties` to the next SNAPSHOT.

## Checking locally

Nothing here uploads.

```bash
./gradlew verifyPublishConfig                     # coordinates of every published module
./gradlew -Pversion=0.1.0 nmcpZipAggregation      # the exact bundle a release uploads
unzip -l build/nmcp/zip/aggregation.zip
./gradlew -Pversion=0.1.0 nmcpCheckAggregationFiles
```

The bundle is signed if `~/.gradle/gradle.properties` has `signing.gnupg.keyName` (and
`signing.gnupg.passphrase`); otherwise the `.asc` files are missing, which is fine for checking
everything else.

To try the tag-to-version logic:

```bash
git tag v0.0.0-test && ./gradlew properties -q | grep '^version:'   # version: 0.0.0-test
git tag -d v0.0.0-test
```

## Troubleshooting

- **"Gradle version does not match the tag"**: the tag is not on the checked-out commit, or is not
  `vX.Y.Z[-suffix]`. Check `git describe --tags --exact-match HEAD`.
- **"is not on main"**: the tag points at a commit that has not landed on `main`. Delete the tag
  (`git push --delete origin vX.Y.Z`) and tag a commit on `main`.
- **GPG errors** ("No secret key", "Inappropriate ioctl"): check `GPG_PRIVATE_KEY` is base64,
  `GPG_KEY_ID` matches it, and the public key is on the keyservers.
- **401 from the Central Portal**: the secrets must be the user token, not the login. Regenerate it
  on the Account page.
- **Deployment fails validation**: see its errors under Deployments at
  https://central.sonatype.com, fix, delete the tag, and release again.

A published version cannot be removed from Maven Central. Fix a bad release with a new patch
version.
