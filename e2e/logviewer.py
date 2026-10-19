# SPDX-FileCopyrightText: Copyright 2026 Diridium Technologies Inc.
# SPDX-License-Identifier: MPL-2.0

"""The plugin's REST calls, the engine and rbac calls the scenarios share, and the known log files
the fresh scenario reads."""

import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

from oie import ApiError

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
EXT = "/extensions/oie-log-viewer"
PLUGIN_PATH = "oie-log-viewer"
MODEL = "com.diridium.logviewer."
VIEW, DOWNLOAD = "View Log Files", "Download Log Files"
# Each endpoint: the permission it needs, and the operation's display name, which the engine's
# event for it starts with (LogViewerServletInterface).
ENDPOINTS = {
    "files": (VIEW, "List log files"),
    "page": (VIEW, "Read a log file page"),
    "search": (VIEW, "Search log files"),
    "count": (VIEW, "Count matching lines in log files"),
    "download": (DOWNLOAD, "Download a log file"),
}
EVENT_SUFFIX = " invoked through OIE Log Viewer"
ARCHIVES = ("mirth.log.1.zip", "mirth.log.2.zip", "mirth.log.3.zip")


def fields(el):
    return {child.tag: child.text or "" for child in el}


# ---------- installing

def install(api, zip_path):
    with open(zip_path, "rb") as f:
        payload = f.read()
    status, text = api.multipart("/extensions/_install",
                                 [("file", os.path.basename(zip_path), "application/zip", payload)])
    return status in (200, 204), f"HTTP {status} {text[:200]}"


def plugin_versions(api):
    """(pluginVersion, mirthVersion) of the plugin the engine loaded, or None if it loaded none."""
    for el in api.xml("GET", "/extensions/plugins").iter("pluginMetaData"):
        if el.get("path") == PLUGIN_PATH:
            return el.findtext("pluginVersion"), el.findtext("mirthVersion")
    return None


def zip_versions(zip_path):
    """(pluginVersion, mirthVersion) from a plugin zip's plugin.xml."""
    with zipfile.ZipFile(zip_path) as z:
        root = ET.fromstring(z.read(PLUGIN_PATH + "/plugin.xml"))
    return root.findtext("pluginVersion"), root.findtext("mirthVersion")


# ---------- the plugin's endpoints

def files(api):
    """{name: fields} of the listed files, and the list's warnings."""
    root = api.xml("GET", EXT + "/files")
    listed = {}
    for el in root.iter(MODEL + "LogFileInfo"):
        f = fields(el)
        listed[f["name"]] = f
    return listed, [w.text for w in root.find("warnings")]


def page(api, file_id, anchor):
    return fields(api.xml("GET", EXT + "/page", query={"fileId": file_id, "anchor": anchor}))


def _query(query, regex, case_sensitive, file_id):
    q = {"query": query, "regex": str(regex).lower(), "caseSensitive": str(case_sensitive).lower()}
    if file_id:
        q["fileId"] = file_id
    return q


def search(api, query, regex=False, case_sensitive=True, file_id=None):
    """The result's own fields, and its matches."""
    root = api.xml("GET", EXT + "/search", query=_query(query, regex, case_sensitive, file_id))
    return fields(root), [fields(m) for m in root.iter(MODEL + "LogSearchMatch")]


def count(api, query, regex=False, case_sensitive=True, file_id=None):
    """The result's own fields, and {file id: matching lines}."""
    root = api.xml("GET", EXT + "/count", query=_query(query, regex, case_sensitive, file_id))
    counts = (fields(c) for c in root.iter(MODEL + "LogFileMatchCount"))
    return fields(root), {c["fileId"]: int(c["matchingLines"]) for c in counts}


def download(api, file_id):
    """(status, body bytes). The body is the file's raw bytes, which Api.request would decode as text."""
    url = api.base + EXT + "/download?" + urllib.parse.urlencode({"fileId": file_id})
    req = urllib.request.Request(url, headers={"X-Requested-With": "OpenAPI", "Accept": "application/octet-stream"})
    try:
        with api.opener.open(req, timeout=300) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def call(api, endpoint, file_id):
    """One request to an endpoint as api's user, the way the viewer makes it. Returns (status, body text)."""
    if endpoint == "download":
        status, body = download(api, file_id)
        return status, body.decode("utf-8", "replace")
    query = {"files": None,
             "page": {"fileId": file_id, "anchor": "TAIL"},
             "search": _query("INFO", False, True, file_id),
             "count": _query("INFO", False, True, file_id)}[endpoint]
    return api.request("GET", f"{EXT}/{endpoint}", query=query)


def plugin_events(api, limit=50):
    """The engine's events for the plugin's requests, newest first: (operation display name, {attribute: value})."""
    found = []
    for event in api.xml("GET", "/events", query={"limit": limit}):
        name = event.findtext("name") or ""
        if name.endswith(EVENT_SUFFIX):
            attributes = {entry[0].text: (entry[1].text or "").strip() for entry in event.iter("entry")}
            found.append((name[:-len(EVENT_SUFFIX)], attributes))
    return found


# ---------- the same answers as JSON, the form the web administrator asks for

def _json(api, path, query=None):
    status, text = api.request("GET", EXT + path, query=query, accept="application/json")
    if status != 200:
        raise ApiError(f"GET {EXT}{path} as JSON -> HTTP {status}: {text[:300]}")
    return json.loads(text)


def _keys(value):
    return [k for k in value if not k.startswith("@")]


def _root(raw, field):
    """raw, or the object in its single wrapper key when `field` isn't at the top (root in log-core.js)."""
    if isinstance(raw, dict) and field not in raw and len(_keys(raw)) == 1 and isinstance(raw[_keys(raw)[0]], dict):
        return raw[_keys(raw)[0]]
    return raw


def _scalar(value):
    """A value XStream may have wrapped in a single key (scalar in log-core.js)."""
    while isinstance(value, dict) and len(_keys(value)) == 1:
        value = value[_keys(value)[0]]
    return value


def _list_of(value, item):
    """The elements of an XStream list: an array, one bare object, or "" when empty (listOf in log-core.js)."""
    if value is None or value == "":
        return []
    if isinstance(value, list):
        return value
    if not isinstance(value, dict):
        return [value]
    if not _keys(value):
        return []
    if len(_keys(value)) == 1 and _keys(value)[0].split(".")[-1].lower() == item.lower():
        inner = value[_keys(value)[0]]
        return [] if inner is None or inner == "" else inner if isinstance(inner, list) else [inner]
    return [value]


def files_json(api):
    """The file list as JSON: {name: id}."""
    r = _root(_json(api, "/files"), "files")
    return {str(_scalar(f.get("name"))): str(_scalar(f.get("id"))) for f in _list_of(r.get("files"), "LogFileInfo")}


def page_json(api, file_id, anchor):
    """A page as JSON: (start offset, end offset, text)."""
    r = _root(_json(api, "/page", {"fileId": file_id, "anchor": anchor}), "startOffset")
    return int(_scalar(r["startOffset"])), int(_scalar(r["endOffset"])), str(_scalar(r.get("text")) or "")


# ---------- known log files

def seed_logs(engine):
    """Writes mirth.log and three archives into the engine's logs folder with scripts/make-test-logs.py,
    before its first start. log4j then appends to that mirth.log, and the archives' names match the
    engine's rollover pattern (mirth.log.%i.zip). Small enough that the engine doesn't roll over (at
    500 KB) during a run."""
    logs = os.path.join(engine.home, "logs")
    os.makedirs(logs, exist_ok=True)   # the distribution ships it empty
    subprocess.run([sys.executable, os.path.join(REPO, "scripts", "make-test-logs.py"), "--dir", logs,
                    "--active-mb", "0.1", "--archives", str(len(ARCHIVES)), "--archive-mb", "0.3", "--seed", "7"],
                   check=True, capture_output=True)


def on_disk(engine, name):
    with open(os.path.join(engine.home, "logs", name), "rb") as f:
        return f.read()


def archive_text(engine, name):
    """The single file inside an archive, decoded."""
    with zipfile.ZipFile(os.path.join(engine.home, "logs", name)) as z:
        return z.read(z.namelist()[0]).decode("utf-8")


def matching_lines(text, test):
    """How many lines pass test, split the way the plugin splits them: at LF, CRLF or a lone CR."""
    return sum(1 for line in re.split(r"\r\n|\r|\n", text) if test(line))


# ---------- users and rbac roles

def create_user(api, username, password):
    """Creates an engine user and sets its password. Returns the user's id."""
    user = ET.Element("user")
    for tag, text in (("username", username), ("firstName", "LV"), ("lastName", "e2e"),
                      ("email", f"{username}@example.com")):
        ET.SubElement(user, tag).text = text
    api.xml("POST", "/users", ET.tostring(user, encoding="unicode"))
    uid = api.xml("GET", f"/users/{username}").findtext("id")
    status, text = api.request("PUT", f"/users/{uid}/password", password, content_type="text/plain")
    if status not in (200, 204) or "<string>" in text:   # a list of strings is the password rules it broke
        raise ApiError(f"PUT /users/{uid}/password -> HTTP {status}: {text[:300]}")
    return uid


RBAC = "/extensions/rbac"


def rbac_answers(api):
    """rbac is installed and running: its role list answers."""
    return api.request("GET", RBAC + "/roles")[0] == 200


def create_role(api, name, permissions, channel_ids=()):
    """An rbac role. An empty channel_ids leaves the role unrestricted by channel. Returns the role's id."""
    role = ET.Element("com.diridium.rbac.Role")
    ET.SubElement(role, "name").text = name
    ET.SubElement(role, "description").text = "oie-log-viewer e2e"
    for tag, values in (("permissions", permissions), ("channelIds", channel_ids)):
        holder = ET.SubElement(role, tag)
        for value in values:
            ET.SubElement(holder, "string").text = value
    ET.SubElement(role, "isAdmin").text = "false"
    return api.xml("POST", RBAC + "/roles", ET.tostring(role, encoding="unicode")).findtext("id")


def assign_role(api, user_id, role_id):
    api.xml("POST", f"{RBAC}/users/{user_id}/role/{role_id}")
