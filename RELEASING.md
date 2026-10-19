# Releasing your own build

How to build and release your own copy of the OIE Log Viewer, a patched fork for example. Diridium
releases the plugin with its own signing key and an internal checklist; this file used to describe
that process and now describes the general one.

## 1. Build from source

```
./scripts/install-engine-jars.sh
mvn -B -ntp clean package
```

The script downloads the OIE distribution for the POM's `mc.version`, checks it against the SHA-256
in `oie.dist.sha256`, and installs the four engine jars the plugin compiles against into your local
Maven repository. Run it again whenever `mc.version` changes. The build runs the server, client and
web tests and writes `package/target/oie-log-viewer-X.Y.Z.zip`, the file the engine installs. Don't
build a release with `-DskipTests`. The warnings a normal build prints are listed in
[docs/design-notes.md](docs/design-notes.md), under "Expected build output".

## 2. Set the version and the engine

The version is in five places, and they must agree:

- `pom.xml`: `<revision>`
- `oie.json`: `version`
- `package/webadmin/plugin.json`: `version`
- `package/webadmin/package.json` and `package-lock.json`: run
  `npm version X.Y.Z --no-git-tag-version` in `package/webadmin`, which updates both.

The engine loads a plugin only on the exact engine version its `plugin.xml` names
(`mirthVersion`), so each OIE release needs a build of its own. The build takes that version from
`plugin.supported.mc.versions` in `pom.xml`, normally `${mc.version}`. To build for another engine,
set `mc.version`, set `oie.dist.sha256` from that release's `sha256sums` file, and set
`minEngineVersion` and `maxEngineVersion` in `oie.json` to the same version. Log files are found
through the engine's log4j configuration, so check that the viewer still lists them on the new
engine.

If the engine you target isn't published yet, leave `mc.version` and `oie.dist.sha256` on the
previous engine and set `plugin.supported.mc.versions` to the new one, as the comment above it in
`pom.xml` explains.

## 3. Release with GitHub Actions

On a fork, enable Actions first: GitHub leaves them off for forks until you turn them on. Then tag a
commit on `main` whose Build run passed:

```
git tag vX.Y.Z
git push origin vX.Y.Z
```

`release.yml` runs with your repository's own `GITHUB_TOKEN`. It:

- refuses a tag whose release is already published;
- waits for the tagged commit's Build run on `main`, and refuses the tag unless every job in it
  passed, the end-to-end tests included;
- checks the tag against the five version files, naming any that differ, and `oie.json`'s engine
  range against the engine `plugin.xml` names;
- builds, runs the tests, and creates a **draft** release with `oie-log-viewer-X.Y.Z.zip` and its
  `.sha256`.

Nothing is published automatically. Review the draft and publish it by hand.

A draft nobody has downloaded can be thrown away and tagged again under the same version. Pushing a
tag the remote already has does nothing, so delete both first:

```
gh release delete vX.Y.Z --yes          # the draft only; never a published release
git push origin :refs/tags/vX.Y.Z
git tag -d vX.Y.Z
```

A published release's files are never replaced: if something is wrong, release a new version.

## 4. Signing (optional)

The engine installs and loads unsigned plugin jars: every CI build is unsigned, and so is every
build the end-to-end tests install. Sign only if you want the jars to carry your certificate.

The `signing` profile signs the plugin's three jars with `jarsigner` before they go into the zip.
Its settings, in the root `pom.xml`, are for a key on a hardware token: `signing.keystore`,
`signing.storetype`, `signing.providerClass` and `signing.providerArg` (the token's PKCS#11
configuration), `signing.alias`, `signing.storepass`, `signing.certchain` (a PEM file with the
certificate chain) and `signing.tsa` (the timestamp server). For a certificate in a PKCS#12 file,
override them on the command line:

```
read -rs STOREPASS
mvn -B -ntp -Psigning clean package \
  -Dsigning.keystore=/path/to/your.p12 -Dsigning.storetype=PKCS12 \
  -Dsigning.providerClass= -Dsigning.providerArg= \
  -Dsigning.alias=your-alias -Dsigning.storepass="$STOREPASS" \
  -Dsigning.certchain=/path/to/certchain.pem
unset STOREPASS
```

The timestamp server stays DigiCert's public one unless you set `signing.tsa`. Then replace the
draft's zip and `.sha256` with the signed ones, from `package/target`:

```
shasum -a 256 oie-log-viewer-X.Y.Z.zip > oie-log-viewer-X.Y.Z.zip.sha256
gh release upload vX.Y.Z oie-log-viewer-X.Y.Z.zip oie-log-viewer-X.Y.Z.zip.sha256 --clobber
```

Replace both: the `.sha256` CI made matches its own unsigned zip. A later `mvn clean` deletes the
signed zip, so upload it before you build again.

## 5. Check the result

```
shasum -a 256 -c oie-log-viewer-X.Y.Z.zip.sha256
mkdir check-X.Y.Z && unzip -q oie-log-viewer-X.Y.Z.zip -d check-X.Y.Z
for j in check-X.Y.Z/oie-log-viewer/*.jar; do jarsigner -verify -strict "$j" || echo "FAILED: $j"; done
```

A signed jar should report "jar verified.". With `-strict`, a certificate that doesn't chain to a
root your JDK trusts, a self-signed one for example, fails even though the jar is signed; plain
`jarsigner -verify` still reports it verified. An unsigned build reports "jar is unsigned.", which
`-strict` also counts as a failure. (`shasum -a 256` writes the same line as `sha256sum`, which
macOS does not ship by default.)

Then install the zip on a test engine of the version it targets, restart the engine, and work
through [docs/release-checks.md](docs/release-checks.md).

## 6. Installing it

The OIE Community Store lists plugins from its catalog by plugin id, the `id` in `oie.json`. A
fork that wants its own listing needs its own id. Otherwise, install the zip directly: in the
Administrator's Extensions view, install it from the file, then restart the engine.
