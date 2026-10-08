# Releasing

A release is built twice: CI builds an unsigned copy and stages it as a draft release, then the
signed copy is built on the Mac that holds the YubiKey and replaces it. Nothing is published
automatically.

## 1. Before tagging

- **Set the version everywhere.** It lives in five places, and they must agree:
  - `pom.xml`: `<revision>`
  - `oie.json`: `version`
  - `package/webadmin/plugin.json`: `version`
  - `package/webadmin/package.json` and `package-lock.json`: run
    `npm version X.Y.Z --no-git-tag-version` in `package/webadmin`, which updates both.
- **For a new engine version**, also update `mc.version` and `oie.dist.sha256` (the SHA-256 of the
  engine's release tarball, which `scripts/install-engine-jars.sh` checks) in `pom.xml`, and
  `minEngineVersion` and `maxEngineVersion` in `oie.json`.
  The engine loads an extension only on the exact version it was
  built for (`ExtensionLoader.isExtensionCompatible`), and the store listing is capped with
  `maxEngineVersion` for that reason. Check log file discovery on the new engine before releasing:
  it reads the engine's log4j configuration.
- **Build and test:** `mvn -B -ntp clean package`, never with `-DskipTests`, and check that
  surefire report files were written. The expected warnings are listed in
  [docs/design-notes.md](docs/design-notes.md).
- **Check by hand:** install the build on a test engine and run
  [docs/release-checks.md](docs/release-checks.md) in both administrators.
- Commit, and push to `main`.

## 2. Tag: CI stages a draft

```
git tag vX.Y.Z
git push origin vX.Y.Z
```

`release.yml` checks that the tag matches all five version files (naming any that do not), refuses
a tag whose release is already published, builds and runs the tests, and creates a **draft**
release with the unsigned `oie-log-viewer-X.Y.Z.zip` and its `.sha256`.

Pushing a tag the remote already has does nothing. To rebuild a draft, delete the draft release and
the remote tag, then tag and push again. **Never re-run the workflow or re-push the tag once step 4
has put the signed files on the draft:** the rebuild uploads the unsigned files over them. Only a
published release is refused.

## 3. Build and sign on the Mac

```
git clone https://github.com/diridium-com/oie-log-viewer.git
cd oie-log-viewer
git checkout vX.Y.Z
./scripts/install-engine-jars.sh
# yubikey-pkcs11.cfg and certchain.pem go in this directory, as for the other plugins:
# the signing profile reads them from the directory Maven runs in.
YUBIKEY_PIN=... mvn -B -ntp -Psigning clean package
```

**Copy the signed zip out of `package/target` straight away.** The next `mvn clean`, anyone's,
deletes it, and it cannot be rebuilt without the key.

Then make its checksum file and check the signatures:

```
shasum -a 256 oie-log-viewer-X.Y.Z.zip > oie-log-viewer-X.Y.Z.zip.sha256
unzip -o -d check oie-log-viewer-X.Y.Z.zip
for j in check/oie-log-viewer/*.jar; do jarsigner -verify "$j"; done
```

Each jar should report "jar verified". (`shasum -a 256` writes the same `<hash>  <name>` line as
`sha256sum`, which macOS does not ship by default.)

## 4. Replace the draft's files and publish

```
gh release upload vX.Y.Z oie-log-viewer-X.Y.Z.zip oie-log-viewer-X.Y.Z.zip.sha256 --clobber
```

Replace **both** files: a checksum file left from the CI build no longer matches the signed zip.
Write the release notes, then publish the draft by hand. Release notes:

- describe what users will see, not the code that changed;
- credit Chris Gibson up front for the OIE Web Administrator and its plugin framework, which the
  web UI is built on;
- say that the web UI needs the Web Support plugin, and that installing on an engine without the
  web administrator is safe (the web files are inert there).

## 5. Check the published release

Download the published zip, check it against its `.sha256`, and run `jarsigner -verify` on its jars
again. A published release's files are never replaced: if something is wrong, release a new
version. An unpublished draft can be deleted and re-tagged under the same version.

## 6. Submit to the Community Store

Only after the release is published: open a pull request on
[gibson9583/oie-community-catalog](https://github.com/gibson9583/oie-community-catalog) from a fork
of it (we have read access only, so nothing can push there directly). The request adds
`manifests/plugins/oie-log-viewer/X.Y.Z.json`, and `meta.json` for the first release, built from
the tagged `oie.json`, with the SHA-256 of the published zip, checked against the release's
`.sha256` first. The catalog's own checks download the zip again before the request can merge, and
connected stores pick it up on their next sync. We do this with the `/publish-to-oie-store` Claude
Code skill.
