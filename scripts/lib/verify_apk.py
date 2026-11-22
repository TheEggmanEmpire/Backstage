#!/usr/bin/env python3
"""Static checks of a built Backstage APK (driven by scripts/verify-apk.sh).

Works on the APK only (apksigner + aapt2 output), so the same checks run on a debug build, on the signed
release build and on an APK downloaded from a GitHub release. Every result line starts with PASS, FLAG or
FAIL; the exit status is 1 when anything FAILed. FLAG lines are known, explicitly classified exposures (mostly upstream
Dextop) that are accepted but should be re-read whenever they change.

Checks
  signing     apksigner verify: v2 or v3 scheme; --release: not the Android debug certificate
              (BACKSTAGE_EXPECT_CERT_SHA256 pins the release certificate when set)
  badging     applicationId, versionName/versionCode, min/target SDK, application label "Backstage" and exactly one
              launcher entry ("Backstage" = Dextop's launcher, Home with the AI tab), debuggable only in debug builds,
              --release: native code limited to the target ABI
  manifest    exactly one phone launcher (MAIN + LAUNCHER): Dextop's launcher activity, exported; no other activity
              with a MAIN filter except Dextop's desktop layer (SECONDARY_HOME, not exported), no launcher-like
              category anywhere else (INFO, LEANBACK/CAR_LAUNCHER, LAUNCHER_APP, HOME), no activity-alias, no static
              shortcuts; the agent screen (AI tab) declared and not a launcher;
              exported components: guarded / intentionally public / policy-listed (FLAG) / unknown (FAIL);
              foreground service types + FOREGROUND_SERVICE_* permissions; exactly ONE accessibility service
              (Dextop's MirrorService, which also serves the agent) with the combined configuration, never the
              retired agent service; no Car Companion pieces (relay, its receiver and activities, CARDEX_RELAY);
              declared permissions and provider authorities namespaced to the applicationId (installs next to
              upstream Dextop); exactly one ShizukuProvider
  telemetry   no advertising-ID / ad-services / install-referrer permission, no Firebase init provider, no Analytics
              measurement component, no ad-services library; Analytics collection deactivated by meta-data 
  notices     every native library (lib/<abi>/*.so, assets/**/*.so) has an entry in assets/backstage/
              THIRD_PARTY_NOTICES.txt (from licenses/native-libraries.tsv) and the texts that entry names are in it
  closed-source closed-source Google SDKs found in the dex (FLAG) must be disclosed by name in NOTICE.md (else FAIL)

The Car Companion (applicationId com.eggman.backstage.cardex) is not built or shipped (1.0.0): such an APK FAILs.
"""
import argparse
import os
import re
import subprocess
import sys
import zipfile

ANDROID_NS = "http://schemas.android.com/apk/res/android:"
APP_ID = "com.eggman.backstage"
# Backstage's launcher opens Home, including the desktop and Agent tab. The agent screen itself is not a launcher.
LAUNCHER = ("moe.n4tsu.dextop.DextopLauncherActivity", "Backstage")
APP_LABEL = "Backstage"
AGENT_ACTIVITY = "com.eggman.backstage.ui.AgentScreenActivity"
# These components must stay absent so cached home-screen entries cannot resolve to a second launcher.
RETIRED_LAUNCHER_COMPONENTS = ("com.eggman.backstage.ui.AgentActivity",)
# The only other activity with a MAIN filter: Dextop's desktop layer, started as the secondary HOME of the desktop
# display (never offered as a launcher; not exported). Its filter must stay exactly this.
DESKTOP_LAYER = ("moe.n4tsu.dextop.plasma.PlasmaDesktopActivity",
                 {"android.intent.category.SECONDARY_HOME", "android.intent.category.DEFAULT"})
# Categories a home screen (One UI Home included) may list or start as an app entry; none may appear anywhere else.
LAUNCHER_LIKE_CATEGORIES = {
    "android.intent.category.LAUNCHER", "android.intent.category.INFO", "android.intent.category.LEANBACK_LAUNCHER",
    "android.intent.category.CAR_LAUNCHER", "android.intent.category.LAUNCHER_APP", "android.intent.category.HOME",
    "android.intent.category.SECONDARY_HOME",
}

# Services that call startForeground (grep of upstream/dextop + backstage sources). On targetSdk >= 34 each
# needs a foregroundServiceType, or startForeground throws MissingForegroundServiceTypeException.
FGS_SERVICES = {
    "moe.n4tsu.dextop.privilege.EmbeddedPairingService",
    "moe.n4tsu.dextop.linux.LinuxSessionService",
    "com.eggman.backstage.agent.AgentService",
    "com.eggman.backstage.voice.WakeWordService",
    "com.eggman.backstage.overlay.BubbleService",
}

# foregroundServiceType bit -> (name, permission required on API 34+; None = no type permission)
FGS_TYPES = {
    0x00000001: ("dataSync", "android.permission.FOREGROUND_SERVICE_DATA_SYNC"),
    0x00000002: ("mediaPlayback", "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"),
    0x00000004: ("phoneCall", "android.permission.FOREGROUND_SERVICE_PHONE_CALL"),
    0x00000008: ("location", "android.permission.FOREGROUND_SERVICE_LOCATION"),
    0x00000010: ("connectedDevice", "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"),
    0x00000020: ("mediaProjection", "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"),
    0x00000040: ("camera", "android.permission.FOREGROUND_SERVICE_CAMERA"),
    0x00000080: ("microphone", "android.permission.FOREGROUND_SERVICE_MICROPHONE"),
    0x00000100: ("health", "android.permission.FOREGROUND_SERVICE_HEALTH"),
    0x00000200: ("remoteMessaging", "android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING"),
    0x00000400: ("systemExempted", "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED"),
    0x00000800: ("shortService", None),
    0x00002000: ("mediaProcessing", "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING"),
    0x40000000: ("specialUse", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"),
}

# Accessibility: the app has exactly ONE service, Dextop's MirrorService (the desktop engine), whose configuration
# (backstage/overlay/res/xml/mirror_accessibility_service.xml) also carries what the agent needs; the glue hands it to
# the library's AccessibilityBridge. name -> expected config (attributes that must be true, required flag and event bits).
FLAG_INCLUDE_NOT_IMPORTANT_VIEWS = 0x2
FLAG_REPORT_VIEW_IDS = 0x10
FLAG_RETRIEVE_INTERACTIVE_WINDOWS = 0x40
FLAG_SEND_MOTION_EVENTS = 0x4000
# AccessibilityEvent types: upstream's (clicked, focused, text changed, selection changed, window state, windows
# changed) + the agent's (content changed and scrolled for UI-settle detection, notifications for get_notifications).
EVENTS_UPSTREAM = 0x1 | 0x8 | 0x10 | 0x2000 | 0x20 | 0x400000
EVENTS_AGENT = 0x800 | 0x1000 | 0x40
A11Y_SERVICES = {
    "moe.n4tsu.dextop.MirrorService": {
        "true": ["canRetrieveWindowContent", "canPerformGestures", "canTakeScreenshot",
                 "isAccessibilityTool"],
        "flags": FLAG_RETRIEVE_INTERACTIVE_WINDOWS | FLAG_SEND_MOTION_EVENTS |
                 FLAG_REPORT_VIEW_IDS | FLAG_INCLUDE_NOT_IMPORTANT_VIEWS,
        "events": EVENTS_UPSTREAM | EVENTS_AGENT,
        "label": "Backstage",
    },
}
# Backstage had a second, separate accessibility service for the agent; it must never come back.
RETIRED_A11Y_SERVICES = ("com.eggman.backstage.a11y.AgentAccessibilityService",)
# Dextop components that only served the Car Companion (removed in by backstage/overlay/AndroidManifest.github.xml).
REMOVED_CAR_COMPONENTS = (
    "moe.n4tsu.dextop.CardexRelayService",
    "moe.n4tsu.dextop.CardexRecoveryReceiver",
    "moe.n4tsu.dextop.CarCompanionPhoneActivity",
    "moe.n4tsu.dextop.AndroidAutoMirrorActivity",
)

# Explicit policy for exported components without permissions. "public": accepted by design (the reason says what
# protects it). "flag": accepted upstream exposure that is reported as FLAG on every run.
REVIEWED = {
    "com.eggman.backstage.automation.BroadcastApiReceiver":
        ("public", "broadcast API: every command must carry the access token (BroadcastApi.parse, constant-time)"),
    "com.eggman.backstage.automation.LocaleFireReceiver":
        ("public", "Locale/Tasker plugin: bundles are verified against the plugin signing secret"),
    "moe.n4tsu.dextop.privilege.EmbeddedPrivilegeProvider":
        ("public", "upstream: call() only acts for Process.SHELL_UID callers"),
    "moe.n4tsu.dextop.MainActivity":
        ("flag", "upstream Flutter UI exported without intent filter (shortcut start-display actions, Stellar "
                 "binder handshake); any app can open the Dextop UI"),
    "moe.n4tsu.dextop.linux.LinuxHostedWindowActivity":
        ("flag", "upstream: started by the generated Linux launcher APKs (other packages); UI only"),
    "moe.n4tsu.dextop.linux.LinuxLaunchActivity":
        ("flag", "upstream: LAUNCH_LINUX_APP from the generated Linux launcher APKs; starts a Linux app"),
}

# The Car Companion's application id: Backstage excludes it from builds and distributions.
COMPANION_ID = APP_ID + ".cardex"

# Broadcasts only the system may send (protected broadcasts).
SYSTEM_BROADCASTS = {
    "android.intent.action.BOOT_COMPLETED",
    "android.intent.action.LOCKED_BOOT_COMPLETED",
    "android.intent.action.MY_PACKAGE_REPLACED",
    "android.intent.action.TIME_SET",
    "android.intent.action.TIMEZONE_CHANGED",
    "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
}
# Activity filters that only open UI the user then drives.
PUBLIC_UI_ACTIONS = {
    "android.intent.action.SEND": "share target",
    "android.intent.action.SEND_MULTIPLE": "share target",
    "android.intent.action.PROCESS_TEXT": "process-text",
    "android.intent.action.ASSIST": "assist entry",
    "android.intent.action.VOICE_COMMAND": "assist entry",
    "android.appwidget.action.APPWIDGET_CONFIGURE": "widget configuration",
    "com.twofortyfouram.locale.intent.action.EDIT_SETTING": "Locale plugin editor",
}


# Telemetry / advertising entries that the measurement SDK (upstream's firebase_analytics) merges into the manifest.
# backstage/overlay/AndroidManifest.github.xml removes them; none may come back.
TELEMETRY_PERMISSIONS = {
    "com.google.android.gms.permission.AD_ID",
    "android.permission.ACCESS_ADSERVICES_AD_ID",
    "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
    "android.permission.ACCESS_ADSERVICES_TOPICS",
    "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
}
TELEMETRY_COMPONENTS = {
    "com.google.firebase.provider.FirebaseInitProvider",
    "com.google.android.gms.measurement.AppMeasurementReceiver",
    "com.google.android.gms.measurement.AppMeasurementService",
    "com.google.android.gms.measurement.AppMeasurementJobService",
}
TELEMETRY_LIBRARIES = {"android.ext.adservices"}
# meta-data that must be present with this value (Analytics off even if a google-services.json is ever added).
TELEMETRY_META = {
    "firebase_analytics_collection_deactivated": "true",
    "google_analytics_adid_collection_enabled": "false",
}

# Closed-source Google SDKs that upstream Dextop pulls in: dex marker -> (what, name NOTICE.md must disclose).
CLOSED_SOURCE_SDKS = [
    (b"Lcom/google/android/gms/measurement/", "Google Analytics for Firebase measurement", "play-services-measurement"),
    (b"Lcom/google/firebase/analytics/", "Firebase Analytics", "firebase-analytics"),
    (b"Lcom/google/android/gms/cast/framework/", "Google Cast SDK", "play-services-cast-framework"),
    (b"Lcom/google/android/gms/common/", "Google Play services base/basement", "play-services-basement"),
    (b"Lcom/google/android/play/core/", "Google Play Core (in-app updates)", "app-update"),
]

NOTICES_ASSET = "assets/backstage/THIRD_PARTY_NOTICES.txt"


class Report:
    def __init__(self):
        self.fails = 0
        self.flags = 0
        self.passes = 0

    def ok(self, msg):
        self.passes += 1
        print(f"PASS {msg}")

    def flag(self, msg):
        self.flags += 1
        print(f"FLAG {msg}")

    def fail(self, msg):
        self.fails += 1
        print(f"FAIL {msg}")

    def check(self, cond, msg, detail=""):
        if cond:
            self.ok(msg)
        else:
            self.fail(msg + (f": {detail}" if detail else ""))
        return cond


def read_text(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def run(cmd):
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    return p.returncode, p.stdout


# ------------------------------------------------------------------------------------------ xmltree parsing
class Elem:
    def __init__(self, tag, indent, parent):
        self.tag, self.indent, self.parent = tag, indent, parent
        self.attrs, self.children = {}, []

    def a(self, name, default=None):
        return self.attrs.get(name, default)

    def find(self, tag):
        return [c for c in self.children if c.tag == tag]

    def walk(self):
        yield self
        for c in self.children:
            yield from c.walk()


ELEM_RE = re.compile(r"^( *)E: (\S+) \(line=\d+\)$")
ATTR_RE = re.compile(r"^( *)A: ([^=]+?)(?:\(0x[0-9a-f]+\))?=(.*)$")


def attr_value(raw):
    m = re.match(r'^"(.*)" \(Raw: ', raw)
    if m:
        return m.group(1)
    if raw.startswith('"') and raw.endswith('"'):
        return raw[1:-1]
    return raw


def parse_xmltree(text):
    root = Elem("#root", -1, None)
    stack = [root]
    for line in text.splitlines():
        m = ELEM_RE.match(line)
        if m:
            indent = len(m.group(1))
            while stack[-1].indent >= indent:
                stack.pop()
            e = Elem(m.group(2), indent, stack[-1])
            stack[-1].children.append(e)
            stack.append(e)
            continue
        m = ATTR_RE.match(line)
        if m:
            indent = len(m.group(1))
            while stack[-1].indent >= indent:
                stack.pop()
            name = m.group(2)
            if name.startswith(ANDROID_NS):
                name = name[len(ANDROID_NS):]
            stack[-1].attrs[name] = attr_value(m.group(3))
    return root


def is_true(v):
    return v is not None and v.lower() in ("true", "0xffffffff", "-1")


def as_int(v, default=0):
    if v is None:
        return default
    try:
        return int(v, 0)
    except ValueError:
        return default


# ------------------------------------------------------------------------------------------------ checks
_SIGNER_DN = re.compile(r"^(?:V\d+(?:\.\d+)? )?Signer\b[^\n]*?certificate DN: (.*)$", re.M)
_SIGNER_SHA256 = re.compile(r"^(?:V\d+(?:\.\d+)? )?Signer\b[^\n]*?certificate SHA-256 digest: ([0-9a-fA-F:]+)\s*$", re.M)


def parse_signers(out):
    """Unique (DN, SHA-256) signers from `apksigner verify --print-certs -v` output.

    apksigner's wording differs between build-tools releases: "Signer #1 certificate DN: …",
    "Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate DN: …" for SDK-targeted v3.1
    signers, and newer releases prefix the scheme ("V2 Signer: certificate DN: …", once per scheme).
    A source-stamp certificate ("Source Stamp Signer …") is not a signer and is not matched. DN and digest lines are
    paired in order and de-duplicated by digest, so one certificate counts once.
    """
    dns = _SIGNER_DN.findall(out)
    digests = [d.lower().replace(":", "") for d in _SIGNER_SHA256.findall(out)]
    result, seen = [], set()
    for i, digest in enumerate(digests):
        if digest in seen:
            continue
        seen.add(digest)
        result.append(((dns[i] if i < len(dns) else "").strip(), digest))
    return result


def check_signing(rep, apksigner, apk, release):
    rc, out = run([apksigner, "verify", "--print-certs", "-v", apk])
    if rc != 0:
        rep.fail(f"apksigner verify failed (exit {rc}): {out.strip().splitlines()[-1] if out.strip() else ''}")
        return []
    schemes = {s: bool(re.search(rf"Verified using {s} scheme \([^)]*\): true", out)) for s in ("v1", "v2", "v3", "v3.1", "v4")}
    used = [s for s, v in schemes.items() if v]
    rep.check(schemes["v2"] or schemes["v3"], f"signature verifies with APK Signature Scheme v2/v3 (schemes: {', '.join(used) or 'none'})")
    found = parse_signers(out)
    signers = [dn for dn, _ in found]
    digests = [d for _, d in found]
    rep.check(len(found) == 1, f"exactly one signer ({'; '.join(signers)})",
              "" if found else "no signer lines parsed; apksigner printed: "
              + " | ".join(l.strip() for l in out.splitlines() if "igner" in l)[:600])
    if release:
        rep.check(not any("CN=Android Debug" in s for s in signers), "release APK is not signed with the Android debug key",
                  "rebuild with BACKSTAGE_KEYSTORE* set")
        pin = os.environ.get("BACKSTAGE_EXPECT_CERT_SHA256", "").lower().replace(":", "")
        if pin:
            rep.check(pin in digests, "release certificate matches BACKSTAGE_EXPECT_CERT_SHA256", f"got {', '.join(digests)}")
    elif any("CN=Android Debug" in s for s in signers):
        rep.ok("debug APK signed with the Android debug key (expected for debug builds)")
    for d in digests:
        print(f"INFO signer certificate SHA-256: {d}")
    return digests


def expected_version(root):
    """versionName/versionCode that backstage/dextop-integration.gradle derives from the repository."""
    try:
        properties = read_text(os.path.join(root, "backstage/version.properties"))
        backstage = re.search(r"^version=([^\s]+)$", properties, re.M).group(1)
        pubspec = read_text(os.path.join(root, "upstream/dextop/pubspec.yaml"))
        up = re.search(r"^version:\s*([^\s#]+)", pubspec, re.M).group(1)
    except (OSError, AttributeError):
        return None, None
    backstage = os.environ.get("BACKSTAGE_VERSION", backstage)
    up_name, _, up_build = up.partition("+")
    parts = [int(p) if p.isdigit() else 0 for p in re.split(r"[.+-]", backstage)[:3]] + [0, 0, 0]
    code = ((parts[0] * 100 + parts[1]) * 100 + parts[2]) * 1000 + (int(up_build or 0) % 1000)
    return backstage, code


def check_badging(rep, aapt2, apk, release, repo_root, abi):
    rc, out = run([aapt2, "dump", "badging", apk])
    if rc != 0:
        rep.fail(f"aapt2 dump badging failed: {out.strip()[:300]}")
        return
    pkg = re.search(r"^package: name='([^']*)' versionCode='(\d+)' versionName='([^']*)'", out, re.M)
    if not rep.check(pkg is not None, "badging has a package line"):
        return
    name, vcode, vname = pkg.group(1), int(pkg.group(2)), pkg.group(3)
    app_id = APP_ID
    rep.check(name == app_id, f"applicationId is {app_id}", f"got {name}")
    want_name, want_code = expected_version(repo_root)
    want_name = os.environ.get("BACKSTAGE_EXPECT_VERSION_NAME", want_name)
    if want_name:
        rep.check(vname == want_name, f"versionName {vname} matches the repository ({want_name})")
    else:
        rep.check(re.fullmatch(r"\d+\.\d+\.\d+", vname) is not None, f"versionName {vname} has the form <major>.<minor>.<patch>")
    if want_code and vname == want_name:
        rep.check(vcode == want_code, f"versionCode {vcode} follows ((major*100+minor)*100+patch)*1000+upstream build", f"want {want_code}")
    base = os.path.basename(apk)
    if base.startswith("backstage-") and re.match(r"backstage-\d", base):
        prefix = "backstage"
        rep.check(base.startswith(f"{prefix}-{vname.replace('+', '_')}-"), f"file name {base} carries versionName {vname} ('+' as '_')")
    min_sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", out, re.M)
    target_sdk = re.search(r"^targetSdkVersion:'(\d+)'", out, re.M)
    min_sdk = int(min_sdk.group(1)) if min_sdk else 0
    target_sdk = int(target_sdk.group(1)) if target_sdk else 0
    want_min = None
    try:
        g = read_text(os.path.join(repo_root, "upstream/dextop/android/app/build.gradle.kts"))
        want_min = int(re.search(r"^\s*minSdk\s*=\s*(\d+)", g, re.M).group(1))
    except (OSError, AttributeError):
        pass
    if want_min:
        rep.check(min_sdk == want_min, f"minSdkVersion {min_sdk} matches upstream app/build.gradle.kts ({want_min})")
    else:
        rep.check(min_sdk >= 33, f"minSdkVersion {min_sdk} >= 33")
    minimum_target = 34
    rep.check(target_sdk >= minimum_target and target_sdk >= min_sdk,
              f"targetSdkVersion {target_sdk} >= {minimum_target} (foreground service types enforced)")
    launch = re.findall(r"^launchable-activity: name='([^']*)'\s+label='([^']*)'", out, re.M)
    rep.check(launch == [LAUNCHER], f"exactly one launcher entry: {LAUNCHER[1]!r} = {LAUNCHER[0].rsplit('.', 1)[-1]}",
              f"got {launch}")
    label = re.search(r"^application-label:'([^']*)'", out, re.M)
    rep.check(label is not None and label.group(1) == APP_LABEL, f"application label is {APP_LABEL!r}",
              f"got {label.group(1) if label else None!r}")
    debuggable = re.search(r"^application-debuggable", out, re.M) is not None
    if release:
        rep.check(not debuggable, "release APK is not debuggable")
        native = re.search(r"^native-code: (.*)$", out, re.M)
        abis = re.findall(r"'([^']+)'", native.group(1)) if native else []
        rep.check(abis == [abi], f"release native code limited to {abi}", f"got {abis}")
    else:
        rep.check(debuggable, "debug APK is debuggable")


def intent_filters(e):
    out = []
    for f in e.find("intent-filter"):
        out.append({
            "actions": [a.a("name") for a in f.find("action")],
            "categories": [c.a("name") for c in f.find("category")],
            "data": [d.attrs for d in f.find("data")],
        })
    return out


def phone_launchers(app):
    """Activities and aliases a phone launcher lists: a MAIN + LAUNCHER filter, not disabled. CAR_LAUNCHER (Android
    Auto) and other launcher categories are not phone launchers. aapt2 badging does not list aliases, so the manifest
    is the source of truth."""
    return sorted({e.a("name") for e in app.children if e.tag in ("activity", "activity-alias")
                   and (e.a("enabled") is None or is_true(e.a("enabled")))
                   for f in intent_filters(e)
                   if "android.intent.action.MAIN" in f["actions"] and "android.intent.category.LAUNCHER" in f["categories"]})


def check_launchers(rep, app):
    """Exactly one launcher icon: Dextop's launcher (Home with the AI tab), exported so the home screen can start it;
    the agent screen is the AI tab, declared but not a launcher."""
    launchers = phone_launchers(app)
    rep.check(launchers == [LAUNCHER[0]],
              f"exactly one phone launcher, {LAUNCHER[0]} (CAR_LAUNCHER aliases are not phone launchers)", f"got {launchers}")
    home = [e for e in app.find("activity") if e.a("name") == LAUNCHER[0]]
    if rep.check(len(home) == 1, f"launcher {LAUNCHER[0]} declared once", f"found {len(home)}"):
        e = home[0]
        exported = e.a("exported")
        exported = is_true(exported) if exported is not None else bool(intent_filters(e))
        rep.check(exported, f"{LAUNCHER[0].rsplit('.', 1)[-1]} is exported (the home screen starts it)")
    agent = [e for e in app.find("activity") if e.a("name") == AGENT_ACTIVITY]
    rep.check(len(agent) == 1, f"agent screen {AGENT_ACTIVITY} declared (the AI tab, not a launcher)", f"found {len(agent)}")
    declared = {e.a("name") for tag in ("activity", "activity-alias") for e in app.find(tag)}
    retired = sorted(declared & set(RETIRED_LAUNCHER_COMPONENTS))
    rep.check(not retired, "no earlier launcher component is declared (a stale launcher entry would resolve again)",
              f"declared: {retired}")
    check_app_entries(rep, app)


def app_entry_problems(app):
    """Everything besides the one launcher a home screen could list or start as an app entry : activity-aliases, MAIN filters other than the launcher and the desktop
    layer, launcher-like categories on any component, static shortcuts (android.app.shortcuts)."""
    problems = []
    for e in app.children:
        name = e.a("name")
        if e.tag == "activity-alias":
            problems.append(f"activity-alias {name}")
        if e.tag not in ("activity", "activity-alias", "service", "receiver"):
            continue
        for f in intent_filters(e):
            acts, cats = set(f["actions"]), set(f["categories"])
            launcherish = cats & LAUNCHER_LIKE_CATEGORIES
            if name == LAUNCHER[0] and e.tag == "activity":
                if launcherish - {"android.intent.category.LAUNCHER"}:
                    problems.append(f"{name}: extra categories {sorted(launcherish)}")
                continue
            if name == DESKTOP_LAYER[0] and e.tag == "activity":
                if acts == {"android.intent.action.MAIN"} and cats == DESKTOP_LAYER[1]:
                    exported = e.a("exported")
                    if exported is not None and is_true(exported):
                        problems.append(f"{name} is exported")
                    continue
                problems.append(f"{name}: filter {sorted(acts)} {sorted(cats)}")
                continue
            if "android.intent.action.MAIN" in acts and e.tag in ("activity", "activity-alias"):
                problems.append(f"{name}: MAIN filter {sorted(cats)}")
            elif launcherish:
                problems.append(f"{name}: categories {sorted(launcherish)}")
        problems += [f"{name}: static shortcuts {m.a('resource')}" for m in e.find("meta-data") if m.a("name") == "android.app.shortcuts"]
    return problems


def check_app_entries(rep, app):
    problems = app_entry_problems(app)
    rep.check(not problems, "no second app entry: no alias, no other MAIN/launcher-like filter (only the desktop layer's "
              "non-exported SECONDARY_HOME), no static shortcuts", f"found {problems}")


def public_reason(kind, e, filters):
    """Why an exported, permission-less component is public by design, or None."""
    reasons = []
    for f in filters:
        acts, cats = set(f["actions"]), set(f["categories"])
        if kind in ("activity", "activity-alias"):
            if acts == {"android.intent.action.MAIN"} and cats & {"android.intent.category.LAUNCHER", "android.intent.category.CAR_LAUNCHER"}:
                reasons.append("launcher")
            elif acts == {"android.intent.action.VIEW"} and "android.intent.category.BROWSABLE" in cats:
                reasons.append("deep link (UI only)")
            elif acts and all(a in PUBLIC_UI_ACTIONS for a in acts):
                reasons.append(PUBLIC_UI_ACTIONS[sorted(acts)[0]])
            else:
                return None
        elif kind == "receiver":
            if acts == {"android.appwidget.action.APPWIDGET_UPDATE"} and any(
                    m.a("name") == "android.appwidget.provider" for m in e.find("meta-data")):
                reasons.append("app widget provider")
            elif acts and acts <= SYSTEM_BROADCASTS:
                reasons.append("system broadcasts only")
            else:
                return None
        else:
            return None
    return ", ".join(dict.fromkeys(reasons)) if reasons else None


def classify_exported(rep, app, declared, reviewed_table):
    """Every exported component: guarded / intentionally public / policy-listed (FLAG) / unknown (FAIL)."""
    kinds = ("activity", "activity-alias", "service", "receiver", "provider")
    seen = set()
    counts = {"guarded": 0, "public": 0, "flag": 0, "private": 0}
    for e in app.children:
        if e.tag not in kinds:
            continue
        name = e.a("name") or "?"
        seen.add(name)
        filters = intent_filters(e)
        exported = e.a("exported")
        exported = is_true(exported) if exported is not None else (bool(filters) and e.tag != "provider")
        if not exported:
            counts["private"] += 1
            continue
        perm = e.a("permission") or (e.a("readPermission") and e.a("writePermission") and e.a("readPermission"))
        if perm:
            if perm.startswith("android.permission."):
                counts["guarded"] += 1
                continue
            level = declared.get(perm)
            if level is not None and level & 0x2:
                counts["guarded"] += 1
                continue
            rep.flag(f"{e.tag} {name} is guarded by {perm}, which is not a platform or signature permission of this APK")
            counts["flag"] += 1
            continue
        reviewed = reviewed_table.get(name)
        if reviewed and reviewed[0] == "public":
            rep.ok(f"exported {e.tag} {name}: intentionally public ({reviewed[1]})")
            counts["public"] += 1
            continue
        if reviewed:
            rep.flag(f"exported {e.tag} {name}: {reviewed[1]}")
            counts["flag"] += 1
            continue
        why = public_reason(e.tag, e, filters)
        if why:
            rep.ok(f"exported {e.tag} {name}: intentionally public ({why})")
            counts["public"] += 1
            continue
        rep.fail(f"exported {e.tag} {name} has no permission and is not reviewed "
                 f"(filters: {[f['actions'] for f in filters]}); guard it, set exported=false, or review it in scripts/lib/verify_apk.py")
    rep.ok(f"exported components classified: {counts['guarded']} guarded (permission/BIND_*), {counts['public']} public by design, "
           f"{counts['flag']} flagged; {counts['private']} not exported")
    for name in sorted(set(reviewed_table) - seen):
        print(f"NOTE reviewed component {name} is not in the manifest (drop it from REVIEWED)")
    return seen


def check_manifest(rep, aapt2, apk):
    rc, out = run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk])
    if rc != 0:
        rep.fail(f"aapt2 dump xmltree failed: {out.strip()[:300]}")
        return None
    root = parse_xmltree(out)
    manifest = next((e for e in root.walk() if e.tag == "manifest"), None)
    if not rep.check(manifest is not None, "manifest parsed"):
        return None
    app = (manifest.find("application") or [None])[0]
    if not rep.check(app is not None, "manifest has an <application>"):
        return None
    uses = {e.a("name") for e in manifest.find("uses-permission") if e.a("name")}
    declared = {e.a("name"): as_int(e.a("protectionLevel"), 0) for e in manifest.find("permission") if e.a("name")}
    app_id = APP_ID
    a11y_rules = A11Y_SERVICES

    # -- declared permissions / Car Companion --------------------------------------------------------------
    relay = [p for p in list(declared) + list(uses) if p.endswith(".CARDEX_RELAY")]
    rep.check(not relay, "no CARDEX_RELAY permission (declared or requested)", f"found {relay}")
    components = {e.a("name") for e in app.children if e.tag in ("activity", "activity-alias", "service", "receiver", "provider")}
    car = sorted(components & set(REMOVED_CAR_COMPONENTS))
    rep.check(not car, "no Car Companion pieces (relay service, its recovery receiver, car display activities)", f"declared: {car}")
    queried = sorted(p.a("name") for q in manifest.find("queries") for p in q.find("package") if ".cardex" in (p.a("name") or ""))
    rep.check(not queried, "no <queries> for a Car Companion package", f"found {queried}")
    foreign = [p for p in declared if not p.startswith(app_id + ".")]
    rep.check(not foreign, f"every declared <permission> is namespaced {app_id}.* (no INSTALL_FAILED_DUPLICATE_PERMISSION next to Dextop)",
              f"found {foreign}")

    # -- telemetry / advertising  -------------------------------------------------------------------
    check_telemetry(rep, manifest, app, uses)

    # -- providers -----------------------------------------------------------------------------------------
    providers = app.find("provider")
    shizuku = [p for p in providers if p.a("name") == "rikka.shizuku.ShizukuProvider"]
    if rep.check(len(shizuku) == 1, f"ShizukuProvider declared exactly once (found {len(shizuku)})"):
        p = shizuku[0]
        rep.check(p.a("authorities") == f"{app_id}.shizuku", f"ShizukuProvider authority is {app_id}.shizuku", f"got {p.a('authorities')}")
        rep.check(p.a("permission") == "android.permission.INTERACT_ACROSS_USERS_FULL",
                  "ShizukuProvider guarded by INTERACT_ACROSS_USERS_FULL (only the system/Shizuku server can call it)")
    authorities = [a for p in providers for a in (p.a("authorities") or "").split(";") if a]
    dupes = sorted({a for a in authorities if authorities.count(a) > 1})
    rep.check(not dupes, "provider authorities are unique", f"duplicates {dupes}")
    foreign = [a for a in authorities if not a.startswith(app_id + ".")]
    rep.check(not foreign, f"every provider authority is namespaced {app_id}.* (no INSTALL_FAILED_CONFLICTING_PROVIDER)",
              f"found {foreign}")

    # -- launcher icon -------------------------------------------------------------------------------------
    check_launchers(rep, app)

    # -- exported components -------------------------------------------------------------------------------
    classify_exported(rep, app, declared, REVIEWED)

    # -- foreground services -------------------------------------------------------------------------------
    services = {s.a("name"): s for s in app.find("service")}
    rep.check("android.permission.FOREGROUND_SERVICE" in uses, "FOREGROUND_SERVICE requested")
    types_used = set()
    for name in sorted(FGS_SERVICES):
        s = services.get(name)
        rep.check(s is not None and as_int(s.a("foregroundServiceType"), 0) != 0,
                  f"foreground service {name} is declared with a foregroundServiceType",
                  "missing service" if s is None else "no foregroundServiceType")
    for name, s in sorted(services.items()):
        bits = as_int(s.a("foregroundServiceType"), 0)
        if not bits:
            continue
        names, missing = [], []
        for bit, (tname, perm) in FGS_TYPES.items():
            if bits & bit:
                names.append(tname)
                types_used.add(bit)
                if perm and perm not in uses:
                    missing.append(perm)
        unknown = bits & ~sum(FGS_TYPES)
        rep.check(not missing and not unknown, f"{name.rsplit('.', 1)[-1]} foregroundServiceType={'|'.join(names)} has its permissions",
                  f"missing {missing}" + (f", unknown bits {unknown:#x}" if unknown else ""))
        if bits & 0x40000000:
            subtype = [p.a("value") for p in s.find("property") if p.a("name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"]
            rep.check(bool(subtype and subtype[0]), f"{name.rsplit('.', 1)[-1]} (specialUse) declares PROPERTY_SPECIAL_USE_FGS_SUBTYPE")
    for bit, (tname, perm) in FGS_TYPES.items():
        if perm and perm in uses and bit not in types_used:
            rep.flag(f"{perm} requested but no service declares foregroundServiceType {tname}")

    # -- accessibility service -----------------------------------------------------------------------------
    a11y = {}
    bound = sorted(n for n, s in services.items()
                   if s.a("permission") == "android.permission.BIND_ACCESSIBILITY_SERVICE"
                   or any("android.accessibilityservice.AccessibilityService" in f["actions"] for f in intent_filters(s)))
    purpose = "desktop and agent"
    rep.check(bound == sorted(a11y_rules), f"exactly one accessibility service: {', '.join(sorted(a11y_rules))} "
              f"({purpose})", f"got {bound}")
    retired = sorted(set(services) & set(RETIRED_A11Y_SERVICES))
    rep.check(not retired, "the retired agent accessibility service is not declared", f"declared: {retired}")
    for name in a11y_rules:
        s = services.get(name)
        if not rep.check(s is not None, f"accessibility service {name} declared"):
            continue
        rep.check(s.a("permission") == "android.permission.BIND_ACCESSIBILITY_SERVICE", f"{name.rsplit('.', 1)[-1]} requires BIND_ACCESSIBILITY_SERVICE")
        acts = [a for f in intent_filters(s) for a in f["actions"]]
        rep.check("android.accessibilityservice.AccessibilityService" in acts, f"{name.rsplit('.', 1)[-1]} has the AccessibilityService intent filter")
        label = s.a("label")
        if label is not None and not label.startswith("@"):
            rep.check(label == a11y_rules[name]["label"], f"{name.rsplit('.', 1)[-1]} is labelled {a11y_rules[name]['label']!r}", f"got {label!r}")
        meta = [m.a("resource") for m in s.find("meta-data") if m.a("name") == "android.accessibilityservice"]
        if rep.check(bool(meta and meta[0]), f"{name.rsplit('.', 1)[-1]} has android.accessibilityservice meta-data"):
            a11y[name] = meta[0]
    return a11y


def check_telemetry(rep, manifest, app, uses):
    ads = sorted(uses & TELEMETRY_PERMISSIONS)
    rep.check(not ads, "no advertising-ID, ad-services or install-referrer permission", f"found {ads}")
    comps = sorted(e.a("name") for e in app.children if e.a("name") in TELEMETRY_COMPONENTS)
    rep.check(not comps, "no Firebase init provider or Analytics measurement component", f"found {comps}")
    libs = sorted(e.a("name") for e in app.find("uses-library") if e.a("name") in TELEMETRY_LIBRARIES)
    rep.check(not libs, "no ad-services library", f"found {libs}")
    meta = {m.a("name"): (m.a("value") or "") for m in app.find("meta-data")}
    def boolean(v):
        if is_true(v):
            return "true"
        return "false" if v is not None and v.lower() in ("false", "0", "0x0", "0x00000000") else v
    wrong = {k: meta.get(k) for k, v in TELEMETRY_META.items() if boolean(meta.get(k)) != v}
    rep.check(not wrong, "Analytics collection deactivated (" + ", ".join(f"{k}={v}" for k, v in TELEMETRY_META.items()) + ")",
              f"got {wrong}")


def check_native_notices(rep, apk):
    """Every native library has an entry in the notices asset, and every text an entry names is in it."""
    try:
        with zipfile.ZipFile(apk) as z:
            names = z.namelist()
            sos = sorted({os.path.basename(n) for n in names
                          if n.endswith(".so") and (n.startswith("lib/") or n.startswith("assets/"))})
            text = z.read(NOTICES_ASSET).decode("utf-8", "replace") if NOTICES_ASSET in names else None
    except (OSError, zipfile.BadZipFile) as e:
        rep.fail(f"cannot read {apk}: {e}")
        return
    if not rep.check(text is not None, f"{NOTICES_ASSET} is packaged", "build with licenses/native-libraries.tsv + :backstage"):
        return
    entries = {}
    for m in re.finditer(r"^== (\S+) ==\n((?:[A-Za-z]+: .*\n)+)", text, re.M):
        texts = re.search(r"^Texts: (.*)$", m.group(2), re.M)
        entries[m.group(1)] = [t.strip() for t in texts.group(1).split(",")] if texts else []
    missing = [so for so in sos if so not in entries]
    rep.check(not missing, f"all {len(sos)} native libraries have a notice entry", f"no entry for {missing} (add them to licenses/native-libraries.tsv)")
    sections = set(re.findall(r"^---- (\S+) ----$", text, re.M))
    absent = sorted({t for so in sos for t in entries.get(so, [])
                     if t not in sections and not any(sec.startswith(t.rstrip("/") + "/") for sec in sections)})
    rep.check(not absent, "every license/notice text the entries name is included in full", f"missing {absent}")
    for required in ("NOTICE.md", "LICENSE"):
        rep.check(required in sections, f"notices asset includes {required}")
    m = re.search(r"^Corresponding source: (\S+) at commit (\S+)$", text, re.M)
    if rep.check(m is not None, "notices asset names the corresponding source (repository + commit)"):
        print(f"INFO corresponding source: {m.group(1)} at {m.group(2)}")
        if m.group(2) == "unknown" or m.group(2).endswith("-dirty"):
            rep.flag(f"built from {m.group(2)} source: no exact commit is its corresponding source")


def check_closed_source_sdks(rep, apk, repo_root):
    try:
        with zipfile.ZipFile(apk) as z:
            dex = b"".join(z.read(n) for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
    except (OSError, zipfile.BadZipFile) as e:
        rep.fail(f"cannot read the dex files: {e}")
        return
    try:
        notice = read_text(os.path.join(repo_root, "NOTICE.md"))
    except OSError:
        notice = ""
    found = [(what, name) for marker, what, name in CLOSED_SOURCE_SDKS if marker in dex]
    for what, name in found:
        if name in notice:
            rep.flag(f"closed-source Google SDK in the dex: {what} ({name}), inherited from upstream Dextop; disclosed in NOTICE.md")
        else:
            rep.fail(f"closed-source Google SDK in the dex: {what} ({name}) is not disclosed in NOTICE.md")
    if not found:
        rep.ok("no closed-source Google SDK found in the dex")


def check_a11y_configs(rep, aapt2, apk, a11y):
    if not a11y:
        return
    rc, out = run([aapt2, "dump", "resources", apk])
    if rc != 0:
        rep.fail("aapt2 dump resources failed")
        return
    files, current = {}, None
    for line in out.splitlines():
        m = re.match(r"^\s+resource (0x[0-9a-f]+) ", line)
        if m:
            current = m.group(1)
            continue
        m = re.match(r"^\s+\(.*?\) \(file\) (\S+)", line)
        if m and current and current not in files:
            files[current] = m.group(1)
    for name, ref in a11y.items():
        short = name.rsplit(".", 1)[-1]
        path = files.get(ref.lstrip("@"))
        if not rep.check(path is not None, f"{short} config resource {ref} resolves to a file"):
            continue
        rc, xml = run([aapt2, "dump", "xmltree", "--file", path, apk])
        cfg = next((e for e in parse_xmltree(xml).walk() if e.tag == "accessibility-service"), None)
        if not rep.check(rc == 0 and cfg is not None, f"{short} config {path} is an <accessibility-service>"):
            continue
        want = (A11Y_SERVICES)[name]
        false = [a for a in want["true"] if not is_true(cfg.a(a))]
        rep.check(not false, f"{short} config: {', '.join(want['true'])} = true", f"not true: {false}")
        flags = as_int(cfg.a("accessibilityFlags"), 0)
        rep.check(flags & want["flags"] == want["flags"], f"{short} config: accessibilityFlags {flags:#x} include {want['flags']:#x}")
        events = as_int(cfg.a("accessibilityEventTypes"), 0)
        rep.check(events & want["events"] == want["events"],
                  f"{short} config: accessibilityEventTypes {events:#x} include upstream's and the agent's {want['events']:#x}")


def package_name(aapt2, apk):
    rc, out = run([aapt2, "dump", "badging", apk])
    m = re.search(r"^package: name='([^']*)'", out, re.M) if rc == 0 else None
    return m.group(1) if m else None


# These components exist only in the GitHub manifest/source set. Check the actual DEX as well as permissions.
UPDATE_COMPONENTS = {
    "com.eggman.backstage.update." + name: kind for name, kind in (
        ("UpdateDownloadService", "service"), ("UpdateAlarmReceiver", "receiver"),
        ("UpdateStatusReceiver", "receiver"), ("UpdateCancelReceiver", "receiver"),
        ("UpdateOpenActivity", "activity"), ("UpdateConfirmationActivity", "activity"),
    )
}


def check_update_distribution(rep, aapt2, apk):
    rc, output = run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk])
    if rc != 0:
        rep.fail("cannot inspect updater manifest: " + output[:300])
        return
    root = parse_xmltree(output)
    manifest = next((entry for entry in root.walk() if entry.tag == "manifest"), None)
    if not rep.check(manifest is not None, "updater manifest parsed"):
        return
    app = (manifest.find("application") or [None])[0]
    if not rep.check(app is not None, "updater manifest has an application"):
        return
    permissions = {p.a("name") for p in manifest.find("uses-permission")}
    components = {p.a("name"): p for p in app.children}
    present = set(components) & set(UPDATE_COMPONENTS)
    install = "android.permission.REQUEST_INSTALL_PACKAGES" in permissions
    marker = b"Lcom/eggman/backstage/update/GitHubReleaseClient;"
    with zipfile.ZipFile(apk) as archive:
        has_checker = any(marker in archive.read(name) for name in archive.namelist()
                          if re.fullmatch(r"classes[0-9]*\.dex", name))
    rep.check(install, "GitHub requests REQUEST_INSTALL_PACKAGES for user-confirmed updates")
    rep.check(present == set(UPDATE_COMPONENTS), "GitHub declares every updater component",
              f"missing {sorted(set(UPDATE_COMPONENTS) - present)}")
    rep.check(all(components[name].a("exported") is not None and not is_true(components[name].a("exported")) for name in present), "updater components are private")
    rep.check(all(components[name].tag == UPDATE_COMPONENTS[name] for name in present), "updater component kinds match their entry points")
    service = components.get("com.eggman.backstage.update.UpdateDownloadService")
    rep.check(service is not None and as_int(service.a("foregroundServiceType"), 0) & 1 != 0 and
              "android.permission.FOREGROUND_SERVICE_DATA_SYNC" in permissions,
              "GitHub updater has the dataSync service type and permission")
    rep.check(has_checker, "GitHub DEX includes the release checker")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--release", action="store_true")
    ap.add_argument("--aapt2", required=True)
    ap.add_argument("--apksigner", required=True)
    ap.add_argument("--repo-root", default=os.path.normpath(os.path.join(os.path.dirname(__file__), "../..")))
    ap.add_argument("--abi", default="arm64-v8a")
    args = ap.parse_args()
    rep = Report()
    companion = (package_name(args.aapt2, args.apk) or "").startswith(COMPANION_ID)
    print(f"== {args.apk} ({'release' if args.release else 'debug'})")
    if companion:
        rep.fail(f"{COMPANION_ID}: Backstage no longer builds or ships the Car Companion (1.0.0)")
        print(f"== {rep.passes} passed, {rep.flags} flagged, {rep.fails} failed")
        return 1
    print("-- signing")
    check_signing(rep, args.apksigner, args.apk, args.release)
    print("-- badging")
    check_badging(rep, args.aapt2, args.apk, args.release, args.repo_root, args.abi)
    print("-- manifest")
    a11y = check_manifest(rep, args.aapt2, args.apk)
    print("-- updater distribution")
    check_update_distribution(rep, args.aapt2, args.apk)
    print("-- accessibility config")
    check_a11y_configs(rep, args.aapt2, args.apk, a11y)
    print("-- notices")
    check_native_notices(rep, args.apk)
    check_closed_source_sdks(rep, args.apk, args.repo_root)
    print(f"== {rep.passes} passed, {rep.flags} flagged, {rep.fails} failed")
    return 1 if rep.fails else 0


if __name__ == "__main__":
    sys.exit(main())
