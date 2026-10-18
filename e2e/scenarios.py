# SPDX-FileCopyrightText: Copyright 2026 Diridium Technologies Inc.
# SPDX-License-Identifier: MPL-2.0

"""The scenarios. Each one gets its own new database and a freshly unpacked engine.

A check that fails is a FAIL and fails the run. A NOTE records something without judging it.
"""

import hashlib
import os
import re
import time
import uuid
import zipfile

import logviewer as lv
from oie import Api

QUERY = "Caused by: java.net.ConnectException"
REGEX = r"Caused by: (java\.net|com\.mysql)\S*"


class Report:
    def __init__(self, scenario):
        self.scenario = scenario
        self.passed = 0
        self.failed = []
        self.notes = []

    def check(self, name, ok, detail=""):
        if ok:
            self.passed += 1
        else:
            self.failed.append(name)
        print(f"{'PASS' if ok else 'FAIL'} [{self.scenario}] {name}" + (f"  [{detail}]" if detail and not ok else ""),
              flush=True)
        return ok

    def note(self, name, value):
        self.notes.append(f"{name}: {value}")
        print(f"NOTE [{self.scenario}] {name}: {value}", flush=True)


# ---------- helpers

def sha256(data):
    return hashlib.sha256(data).hexdigest()


def start(engine):
    """Starts the engine. Returns its API and where in the log this start began."""
    since = len(engine.log())
    return engine.start(), since


def check_quiet(r, engine, since, when):
    bad = engine.plugin_log_lines(("WARN", "ERROR"), since)
    r.check(f"no WARN or ERROR from or about the plugin {when}", not bad, " | ".join(bad)[:600])


def check_loaded(r, api, zip_path, what):
    want = lv.zip_versions(zip_path)
    got = lv.plugin_versions(api)
    r.check(f"the engine loaded {what}: {want[0]} for OIE {want[1]}", got == want, f"loaded: {got}")


def check_server_jar(r, engine, zip_path):
    """The installed server jar must be the zip's, and the only one (an upgrade must not leave the old one)."""
    with zipfile.ZipFile(zip_path) as z:
        name = next(n for n in z.namelist() if re.search(r"oie-log-viewer-server-[^/]*\.jar$", n))
        want = sha256(z.read(name))
    files = engine.extension_files(lv.PLUGIN_PATH) or []
    jars = [f for f in files if f.startswith("oie-log-viewer-server-")]
    got = None
    if len(jars) == 1:
        with open(os.path.join(engine.home, "extensions", lv.PLUGIN_PATH, jars[0]), "rb") as f:
            got = sha256(f.read())
    r.check(f"the installed server jar is {os.path.basename(name)} and the only one",
            jars == [os.path.basename(name)] and got == want, f"found {jars}")


def use_the_viewer(r, api, engine):
    """Every endpoint against the seeded files, each answer compared with the files on disk."""
    listed, warnings = lv.files(api)
    want = ["mirth.log", *lv.ARCHIVES]
    r.check("the list is mirth.log and the 3 seeded archives (found through log4j)", sorted(listed) == sorted(want),
            sorted(listed))
    r.check("the list has no warnings", not warnings, warnings)
    if not all(name in listed for name in want):
        return
    active, oldest = listed["mirth.log"], listed["mirth.log.1.zip"]
    r.check("mirth.log is the active file and the archives are compressed",
            active["active"] == "true" and all(listed[n]["compressed"] == "true" for n in lv.ARCHIVES))

    text = lv.archive_text(engine, "mirth.log.1.zip")
    head = lv.page(api, oldest["id"], "HEAD")
    r.check("mirth.log.1.zip's first page is the start of the archive, HL7's lone CRs kept",
            head["startOffset"] == "0" and len(head["text"]) > 1000 and text.startswith(head["text"])
            and "\rEVN|" in head["text"], f"offset {head['startOffset']}, {len(head['text'])} characters")
    tail = lv.page(api, active["id"], "TAIL")
    data = lv.on_disk(engine, "mirth.log")
    s, e = int(tail["startOffset"]), int(tail["endOffset"])
    r.check("mirth.log's last page is the file's bytes at the page's offsets, up to its end",
            tail["atEnd"] == "true" and e > s and data[s:e].decode("utf-8") == tail["text"], f"{s}-{e} of {len(data)}")

    plain = lv.matching_lines(text, lambda line: QUERY in line)
    result, matches = lv.search(api, QUERY, file_id=oldest["id"])
    r.check(f"a search of mirth.log.1.zip finds the {plain} lines with '{QUERY}'",
            plain > 0 and result["complete"] == "true" and len(matches) == plain
            and all(QUERY in m["lineText"] for m in matches), f"{len(matches)} matches")
    result, matches = lv.search(api, QUERY.upper(), case_sensitive=False, file_id=oldest["id"])
    r.check("the same search in capitals, ignoring case, finds the same lines", len(matches) == plain,
            f"{len(matches)} matches")
    pattern = re.compile(REGEX)
    regex = lv.matching_lines(text, lambda line: pattern.search(line) is not None)
    result, matches = lv.search(api, REGEX, regex=True, file_id=oldest["id"])
    r.check(f"a regular expression search of mirth.log.1.zip finds its {regex} matching lines",
            regex > plain and result["complete"] == "true" and len(matches) == regex
            and all(pattern.search(m["lineText"]) for m in matches), f"{len(matches)} matches")

    result, counts = lv.count(api, QUERY)
    texts = {n: lv.archive_text(engine, n) for n in lv.ARCHIVES}
    texts["mirth.log"] = lv.on_disk(engine, "mirth.log").decode("utf-8")
    want_counts = {listed[n]["id"]: lv.matching_lines(t, lambda line: QUERY in line) for n, t in texts.items()}
    r.check(f"a count over every file gives each file's number of lines with '{QUERY}'",
            result["complete"] == "true" and counts == want_counts, f"{counts} vs {want_counts}")

    status, body = lv.download(api, oldest["id"])
    r.check("a downloaded archive is the zip on disk, byte for byte",
            status == 200 and sha256(body) == sha256(lv.on_disk(engine, "mirth.log.1.zip")), f"HTTP {status}, {len(body)} bytes")
    status, body = lv.download(api, active["id"])
    r.check("the downloaded mirth.log is the active file's bytes",
            status == 200 and len(body) >= int(active["size"]) and lv.on_disk(engine, "mirth.log").startswith(body),
            f"HTTP {status}, {len(body)} bytes")

    # The engine writes events from a queue, so the last request's event can arrive a moment later.
    names = {display for _, display in lv.ENDPOINTS.values()}
    deadline = time.time() + 15
    while True:
        events = lv.plugin_events(api)
        seen = {name for name, _ in events}
        if names <= seen or time.time() > deadline:
            break
        time.sleep(1)
    r.check("the engine's event log has an event for each of the five requests", names <= seen, sorted(seen))
    searches = [attributes for name, attributes in events if name == "Search log files"]
    r.check("a search's event records its query and file",
            any(a.get("query") == QUERY and a.get("fileId") == oldest["id"] for a in searches), str(searches[:2]))


# ---------- scenarios

def fresh(r, engine, db, name, settings, new_zip):
    """Known log files written before the first start, the zip installed, and every endpoint used on them."""
    lv.seed_logs(engine)
    api = engine.start()
    r.check("the zip installs through the engine's installer", *lv.install(api, new_zip))
    engine.stop()
    api, since = start(engine)
    check_loaded(r, api, new_zip, "the build under test")
    check_server_jar(r, engine, new_zip)
    use_the_viewer(r, api, engine)
    engine.stop()
    api, _ = start(engine)
    listed, _ = lv.files(api)
    r.check("after a restart the list is still mirth.log and the 3 archives",
            sorted(listed) == sorted(["mirth.log", *lv.ARCHIVES]), sorted(listed))
    engine.stop()
    check_quiet(r, engine, 0, "on any start")


def upgrade(r, engine, db, name, settings, new_zip, old_zip, upgrade_engine=None):
    """The new zip installed over the old release. With upgrade_engine, the old release runs on the older engine
    it was built for, and the engine is upgraded under it before the new zip goes in: the path every user of the
    old release takes. The plugin keeps no data of its own, so what must carry over is the install itself."""
    api = engine.start()
    r.check("the old release installs", *lv.install(api, old_zip))
    engine.stop()
    api = engine.start()
    check_loaded(r, api, old_zip, "the old release")
    r.check("the old release lists mirth.log", "mirth.log" in lv.files(api)[0])

    if upgrade_engine:
        engine.stop()
        engine = upgrade_engine(engine)
        api = engine.start()
        # Control: the old release is still installed under the upgraded engine, which skips it.
        skipped = [line for line in engine.plugin_log_lines(("ERROR",)) if "is not compatible" in line]
        r.check("after the engine upgrade: the engine skipped the old release", len(skipped) == 1,
                f"{len(skipped)} 'is not compatible' lines")

    r.check("the new zip installs over the old release", *lv.install(api, new_zip))
    engine.stop()
    api, since = start(engine)
    check_loaded(r, api, new_zip, "the build under test")
    check_server_jar(r, engine, new_zip)
    listed, warnings = lv.files(api)
    r.check("after the upgrade: the list has mirth.log and no warnings", "mirth.log" in listed and not warnings,
            f"{sorted(listed)} {warnings}")
    if "mirth.log" in listed:
        tail = lv.page(api, listed["mirth.log"]["id"], "TAIL")
        r.check("after the upgrade: mirth.log's last page reads", tail["atEnd"] == "true" and tail["text"])
    engine.stop()
    check_quiet(r, engine, since, "after the upgrade")


def rbac(r, engine, db, name, settings, new_zip, rbac_zip):
    """rbac installed alongside, with the four roles docs/release-checks.md uses. Admin is the control. The
    plugin refuses a role limited to any channels, whichever channels they are, so that role names a channel
    id the engine doesn't need to have."""
    api = engine.start()
    for z in (new_zip, rbac_zip):
        r.check(f"{os.path.basename(z)} installs", *lv.install(api, z))
    engine.stop()
    api, since = start(engine)
    r.check("rbac is running", lv.rbac_answers(api))
    check_loaded(r, api, new_zip, "the build under test")
    listed, _ = lv.files(api)
    if not r.check("the list has mirth.log", "mirth.log" in listed, sorted(listed)):
        return
    file_id = listed["mirth.log"]["id"]

    roles = {
        "no log permissions": (["viewDashboard"], ()),
        "view only": ([lv.VIEW], ()),
        "view and download": ([lv.VIEW, lv.DOWNLOAD], ()),
        "limited to one channel": ([lv.VIEW, lv.DOWNLOAD], (str(uuid.uuid4()),)),
    }
    allowed = {
        "admin (the control)": set(lv.ENDPOINTS),
        "no log permissions": set(),
        "view only": {"files", "page", "search", "count"},
        "view and download": set(lv.ENDPOINTS),
        "limited to one channel": set(),
    }
    password = "Lv-e2e-" + uuid.uuid4().hex[:12] + "!"
    users = {"admin (the control)": api}
    for i, (label, (permissions, channels)) in enumerate(roles.items()):
        role = lv.create_role(api, f"LV e2e {label}", permissions, channels)
        lv.assign_role(api, lv.create_user(api, f"lv-e2e-{i}", password), role)
        users[label] = Api(engine.port, f"lv-e2e-{i}", password)
        users[label].login(wait=60)

    for label, user in users.items():
        for endpoint in lv.ENDPOINTS:
            status, body = lv.call(user, endpoint, file_id)
            ok = endpoint in allowed[label]
            r.check(f"{label}: {endpoint} is {'allowed' if ok else 'refused'}", status == (200 if ok else 403),
                    f"HTTP {status} {body[:150]}")
    # The channel role has both permissions, so its refusal is the plugin's own, which says why.
    status, body = lv.call(users["limited to one channel"], "files", file_id)
    r.check("limited to one channel: the refusal names the channel restriction", "CHANNEL_RESTRICTED" in body,
            f"HTTP {status} {body[:200]}")
    engine.stop()

    # rbac logs a WARN for each request it refuses, naming the plugin's operation. They must be exactly the
    # requests a role lacked the permission for: all five without log permissions, and view only's download.
    # The channel role passes rbac, so its refusals are the plugin's and log nothing.
    lines = engine.plugin_log_lines(("WARN", "ERROR"), since)
    denial = re.compile(r"RBAC: User \d+ denied for operation 'OIE Log Viewer#(\w+)'")
    denied = sorted(m.group(1) for m in map(denial.search, lines) if m)
    r.check("rbac refused exactly the 6 requests the roles lack the permission for", denied == sorted(
        ["listFiles", "readPage", "search", "count", "download", "download"]), str(denied))
    others = [line for line in lines if not denial.search(line)]
    r.check("no other WARN or ERROR from or about the plugin with rbac", not others, " | ".join(others)[:600])
