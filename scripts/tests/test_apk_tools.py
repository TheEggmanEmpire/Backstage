#!/usr/bin/env python3
"""Unit tests of scripts/lib/verify_apk.py on synthetic input (no SDK, no APK).

    python3 -m unittest discover -s scripts/tests -p 'test_*.py'
"""
import sys
sys.dont_write_bytecode = True  # keep scripts/ free of __pycache__

import contextlib
import importlib.util
import io
import os
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.dirname(HERE)


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


va = load("verify_apk", os.path.join(SCRIPTS, "lib", "verify_apk.py"))

NS = "http://schemas.android.com/apk/res/android:"


def element(depth, tag, attrs, children=()):
    pad = "  " * depth
    out = [f"{pad}E: {tag} (line=1)"]
    for k, v in attrs.items():
        name = k if k in ("package",) else f"{NS}{k}(0x01010003)"
        val = f'"{v}" (Raw: "{v}")' if isinstance(v, str) else str(v).lower()
        out.append(f"{pad}  A: {name}={val}")
    for c in children:
        out.extend(c(depth + 2))
    return out


def E(tag, attrs=None, *children):
    return lambda depth: element(depth, tag, attrs or {}, children)


def action(name):
    return E("action", {"name": name})


A11Y = "android.accessibilityservice.AccessibilityService"


def launcher_activity(name, tag="activity", category="android.intent.category.LAUNCHER", **attrs):
    return E(tag, {"name": name, "exported": True, **attrs},
             E("intent-filter", {}, action("android.intent.action.MAIN"), E("category", {"name": category})))


def agent_activity(launcher=False, **attrs):
    """The agent screen (AI tab): exported for its deep link / share filters, no launcher filter."""
    if launcher:
        return launcher_activity(va.AGENT_ACTIVITY, **attrs)
    return E("activity", {"name": va.AGENT_ACTIVITY, "exported": True, **attrs},
             E("intent-filter", {}, action("android.intent.action.VIEW"),
               E("category", {"name": "android.intent.category.DEFAULT"}),
               E("category", {"name": "android.intent.category.BROWSABLE"})))


def desktop_layer(exported=False, categories=("android.intent.category.SECONDARY_HOME", "android.intent.category.DEFAULT")):
    """Dextop's desktop layer: the secondary HOME of the desktop display, not exported (upstream manifest)."""
    return E("activity", {"name": va.DESKTOP_LAYER[0], "exported": exported},
             E("intent-filter", {}, action("android.intent.action.MAIN"), *[E("category", {"name": c}) for c in categories]))


def a11y_service(name, res="@0x7f140000"):
    return E("service", {"name": name, "permission": "android.permission.BIND_ACCESSIBILITY_SERVICE", "exported": True},
             E("intent-filter", {}, action(A11Y)),
             E("meta-data", {"name": "android.accessibilityservice", "resource": res}))


def base_app(*extra, launchers=None):
    """A manifest that passes every manifest check; extra components are appended to <application>. [launchers]
    replaces Dextop's launcher (Home) + the agent screen (AI tab, not a launcher)."""
    if launchers is None:
        launchers = (launcher_activity(va.LAUNCHER[0]), agent_activity())
    a11y = lambda name, res: E("service", {"name": name, "permission": "android.permission.BIND_ACCESSIBILITY_SERVICE", "exported": True},
                               E("intent-filter", {}, action(A11Y)),
                               E("meta-data", {"name": "android.accessibilityservice", "resource": res}))
    fgs = [E("service", {"name": n, "exported": False, "foregroundServiceType": "0x40000000"},
             E("property", {"name": "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE", "value": "x"}))
           for n in sorted(va.FGS_SERVICES)]
    return E("manifest", {"package": va.APP_ID},
             E("uses-permission", {"name": "android.permission.FOREGROUND_SERVICE"}),
             E("uses-permission", {"name": "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"}),
             E("application", {},
               E("provider", {"name": "rikka.shizuku.ShizukuProvider", "authorities": f"{va.APP_ID}.shizuku", "exported": True,
                              "permission": "android.permission.INTERACT_ACROSS_USERS_FULL"}),
               a11y("moe.n4tsu.dextop.MirrorService", "@0x7f140006"),
               *launchers,
               desktop_layer(),
               E("meta-data", {"name": "firebase_analytics_collection_deactivated", "value": True}),
               E("meta-data", {"name": "google_analytics_adid_collection_enabled", "value": False}),
               *fgs, *extra))


def run_manifest(tree):
    text = "N: android=http://schemas.android.com/apk/res/android (line=2)\n" + "\n".join(tree(1)) + "\n"
    va.run = lambda cmd: (0, text)
    rep = va.Report()
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        va.check_manifest(rep, "aapt2", "x.apk")
    return rep, out.getvalue()


class VerifyApkManifestTest(unittest.TestCase):
    def test_clean_manifest_passes(self):
        rep, out = run_manifest(base_app())
        self.assertEqual(rep.fails, 0, out)

    def test_unknown_exported_receiver_fails(self):
        rep, out = run_manifest(base_app(E("receiver", {"name": "x.Leaky", "exported": True},
                                           E("intent-filter", {}, action("x.DO_IT")))))
        self.assertIn("FAIL exported receiver x.Leaky", out)

    def test_implicitly_exported_service_fails_and_guarded_one_passes(self):
        rep, out = run_manifest(base_app(
            E("service", {"name": "x.Implicit"}, E("intent-filter", {}, action("x.BIND"))),
            E("service", {"name": "x.Guarded", "exported": True, "permission": "android.permission.BIND_JOB_SERVICE"})))
        self.assertIn("FAIL exported service x.Implicit", out)
        self.assertNotIn("x.Guarded", out)

    def test_public_ui_and_system_broadcast_receivers_pass(self):
        rep, out = run_manifest(base_app(
            E("activity", {"name": "x.Share", "exported": True}, E("intent-filter", {}, action("android.intent.action.SEND"))),
            E("receiver", {"name": "x.Boot", "exported": True}, E("intent-filter", {}, action("android.intent.action.BOOT_COMPLETED")))))
        self.assertEqual(rep.fails, 0, out)
        self.assertIn("x.Share: intentionally public (share target)", out)
        self.assertIn("x.Boot: intentionally public (system broadcasts only)", out)

    def test_mixed_system_and_custom_action_receiver_fails(self):
        rep, out = run_manifest(base_app(E("receiver", {"name": "x.Mixed", "exported": True},
                                           E("intent-filter", {}, action("android.intent.action.BOOT_COMPLETED"), action("x.CUSTOM")))))
        self.assertIn("FAIL exported receiver x.Mixed", out)

    def test_cardex_relay_and_second_shizuku_provider_fail(self):
        tree = base_app(E("provider", {"name": "rikka.shizuku.ShizukuProvider", "authorities": f"{va.APP_ID}.shizuku2",
                                       "exported": True, "permission": "android.permission.INTERACT_ACROSS_USERS_FULL"}))
        relay = lambda depth: element(depth, "permission", {"name": "moe.n4tsu.dextop.permission.CARDEX_RELAY", "protectionLevel": "0x00000000"}, ())
        orig = tree

        def with_relay(depth):
            lines = orig(depth)
            return lines[:2] + relay(depth + 2) + lines[2:]
        rep, out = run_manifest(with_relay)
        self.assertIn("FAIL no CARDEX_RELAY permission", out)
        self.assertIn("FAIL ShizukuProvider declared exactly once (found 2)", out)
        self.assertIn("FAIL every declared <permission> is namespaced", out)

    def test_fgs_type_without_permission_fails(self):
        rep, out = run_manifest(base_app(E("service", {"name": "x.Mic", "exported": False, "foregroundServiceType": "0x00000080"})))
        self.assertIn("FAIL Mic foregroundServiceType=microphone has its permissions: missing ['android.permission.FOREGROUND_SERVICE_MICROPHONE']", out)

    def test_missing_accessibility_service_fails(self):
        rep, out = run_manifest(E("manifest", {"package": va.APP_ID}, E("application", {})))
        self.assertIn("FAIL accessibility service moe.n4tsu.dextop.MirrorService declared", out)
        self.assertIn("FAIL exactly one accessibility service: moe.n4tsu.dextop.MirrorService", out)
        self.assertIn("FAIL ShizukuProvider declared exactly once (found 0)", out)

    def test_exactly_one_accessibility_service_and_never_the_retired_agent_service(self):
        rep, out = run_manifest(base_app())
        self.assertIn("PASS exactly one accessibility service: moe.n4tsu.dextop.MirrorService (desktop and agent)", out)
        self.assertIn("PASS the retired agent accessibility service is not declared", out)
        rep, out = run_manifest(base_app(a11y_service(va.RETIRED_A11Y_SERVICES[0])))
        self.assertIn("FAIL exactly one accessibility service", out)
        self.assertIn("FAIL the retired agent accessibility service is not declared", out)
        # Any other service bound as accessibility service counts too, with or without the intent filter.
        rep, out = run_manifest(base_app(E("service", {"name": "x.Other", "permission": "android.permission.BIND_ACCESSIBILITY_SERVICE"})))
        self.assertIn("FAIL exactly one accessibility service", out)
        self.assertIn("x.Other", out)

    def test_car_companion_pieces_must_not_come_back(self):
        rep, out = run_manifest(base_app())
        self.assertIn("PASS no Car Companion pieces", out)
        self.assertIn("PASS no <queries> for a Car Companion package", out)
        for name in va.REMOVED_CAR_COMPONENTS:
            tag = "service" if name.endswith("Service") else "receiver" if name.endswith("Receiver") else "activity"
            rep, out = run_manifest(base_app(E(tag, {"name": name, "exported": False})))
            self.assertIn("FAIL no Car Companion pieces", out)
            self.assertIn(name, out)

        def with_query(depth):
            lines = base_app()(depth)
            return lines[:2] + element(depth + 2, "queries", {}, (E("package", {"name": va.COMPANION_ID}),)) + lines[2:]
        rep, out = run_manifest(with_query)
        self.assertIn("FAIL no <queries> for a Car Companion package", out)

    def test_telemetry_permissions_components_and_library_fail(self):
        def with_ad_id(depth):
            lines = base_app(
                E("provider", {"name": "com.google.firebase.provider.FirebaseInitProvider", "exported": False,
                               "authorities": f"{va.APP_ID}.firebaseinitprovider"}),
                E("service", {"name": "com.google.android.gms.measurement.AppMeasurementJobService", "exported": False,
                              "permission": "android.permission.BIND_JOB_SERVICE"}),
                E("uses-library", {"name": "android.ext.adservices", "required": False}))(depth)
            ad = element(depth + 2, "uses-permission", {"name": "com.google.android.gms.permission.AD_ID"}, ())
            return lines[:2] + ad + lines[2:]
        rep, out = run_manifest(with_ad_id)
        self.assertIn("FAIL no advertising-ID, ad-services or install-referrer permission: found ['com.google.android.gms.permission.AD_ID']", out)
        self.assertIn("FAIL no Firebase init provider or Analytics measurement component", out)
        self.assertIn("AppMeasurementJobService", out)
        self.assertIn("FAIL no ad-services library", out)

    def test_analytics_must_be_deactivated(self):
        tree = base_app()
        text = [l for l in tree(1)]
        # Drop the deactivation meta-data (and its attribute lines) from the dump.
        out_lines, skip = [], False
        for l in text:
            if l.strip().startswith("E: meta-data"):
                skip = True
                continue
            if skip and l.strip().startswith("A: "):
                continue
            skip = False
            out_lines.append(l)
        rep, out = run_manifest(lambda depth: out_lines)
        self.assertIn("FAIL Analytics collection deactivated", out)
        rep, out = run_manifest(base_app())
        self.assertIn("PASS Analytics collection deactivated", out)

    def test_exactly_one_phone_launcher_is_dextops_home(self):
        rep, out = run_manifest(base_app())
        self.assertEqual(rep.fails, 0, out)
        self.assertIn(f"PASS exactly one phone launcher, {va.LAUNCHER[0]}", out)
        self.assertIn("PASS DextopLauncherActivity is exported", out)
        self.assertIn(f"PASS agent screen {va.AGENT_ACTIVITY} declared", out)

    def test_agent_coming_back_as_a_launcher_fails(self):
        # An agent launcher or two launcher icons must fail.
        rep, out = run_manifest(base_app(launchers=(launcher_activity(va.LAUNCHER[0]), agent_activity(launcher=True))))
        self.assertIn("FAIL exactly one phone launcher", out)
        rep, out = run_manifest(base_app(launchers=(agent_activity(launcher=True),
                                                    E("activity", {"name": va.LAUNCHER[0], "exported": False}))))
        self.assertIn("FAIL exactly one phone launcher", out)
        self.assertIn("FAIL DextopLauncherActivity is exported", out)

    def test_a_retired_launcher_component_must_not_come_back(self):
        # A stale One UI entry resolves to the agent screen while that launcher alias exists.
        rep, out = run_manifest(base_app())
        self.assertIn("PASS no earlier launcher component is declared", out)
        old = va.RETIRED_LAUNCHER_COMPONENTS[0]
        rep, out = run_manifest(base_app(E("activity", {"name": old, "exported": True})))
        self.assertIn("FAIL no earlier launcher component is declared", out)
        rep, out = run_manifest(base_app(E("activity-alias", {"name": old, "targetActivity": va.AGENT_ACTIVITY})))
        self.assertIn("FAIL no earlier launcher component is declared", out)

    def test_unexported_launcher_fails(self):
        rep, out = run_manifest(base_app(launchers=(launcher_activity(va.LAUNCHER[0], exported=False), agent_activity())))
        self.assertIn("FAIL DextopLauncherActivity is exported", out)

    def test_launcher_alias_counts_and_every_other_app_entry_fails_too(self):
        alias = launcher_activity("x.SecondIcon", tag="activity-alias", targetActivity=va.LAUNCHER[0])
        rep, out = run_manifest(base_app(alias))
        self.assertIn("FAIL exactly one phone launcher", out)
        self.assertIn("x.SecondIcon", out)
        # Not phone launchers in the strict sense, but a home screen may still list or start them (user report on
        # two "Backstage" icons): a CAR_LAUNCHER alias, a launcher disabled in the manifest (it can be enabled at
        # runtime), an INFO or LAUNCHER_APP entry, a MAIN filter with any other category.
        for extra in (launcher_activity("x.CarIcon", tag="activity-alias", category="android.intent.category.CAR_LAUNCHER",
                                        targetActivity=va.LAUNCHER[0]),
                      launcher_activity("x.Disabled", enabled=False),
                      launcher_activity("x.Info", category="android.intent.category.INFO"),
                      launcher_activity("x.LauncherApp", category="android.intent.category.LAUNCHER_APP"),
                      launcher_activity("x.Default", category="android.intent.category.DEFAULT")):
            rep, out = run_manifest(base_app(extra))
            self.assertIn("FAIL no second app entry", out)

    def test_the_desktop_layer_is_the_only_other_main_activity_and_stays_private(self):
        rep, out = run_manifest(base_app())
        self.assertIn("PASS no second app entry", out)
        rep, out = run_manifest(base_app(launchers=(launcher_activity(va.LAUNCHER[0]), agent_activity(), desktop_layer(exported=True))))
        self.assertIn("FAIL no second app entry", out)
        self.assertIn("PlasmaDesktopActivity is exported", out)
        rep, out = run_manifest(base_app(launchers=(launcher_activity(va.LAUNCHER[0]), agent_activity(),
                                                    desktop_layer(categories=("android.intent.category.LAUNCHER",)))))
        self.assertIn("FAIL no second app entry", out)

    def test_static_shortcuts_fail(self):
        rep, out = run_manifest(base_app(launchers=(
            E("activity", {"name": va.LAUNCHER[0], "exported": True},
              E("intent-filter", {}, action("android.intent.action.MAIN"), E("category", {"name": "android.intent.category.LAUNCHER"})),
              E("meta-data", {"name": "android.app.shortcuts", "resource": "@0x7f150000"})),
            agent_activity())))
        self.assertIn("FAIL no second app entry", out)
        self.assertIn("static shortcuts", out)

    def test_wrong_or_missing_launcher_and_missing_agent_screen_fail(self):
        rep, out = run_manifest(base_app(launchers=(launcher_activity("moe.n4tsu.dextop.MainActivity"),)))
        self.assertIn("FAIL exactly one phone launcher", out)
        self.assertIn(f"FAIL launcher {va.LAUNCHER[0]} declared once", out)
        self.assertIn(f"FAIL agent screen {va.AGENT_ACTIVITY} declared", out)
        rep, out = run_manifest(base_app(launchers=(agent_activity(),)))
        self.assertIn("FAIL exactly one phone launcher", out)

    def test_parser_nests_attributes_and_children(self):
        root = va.parse_xmltree("\n".join(base_app()(1)))
        services = [e for e in root.walk() if e.tag == "service"]
        self.assertTrue(all(e.parent.tag == "application" for e in services))
        self.assertEqual(services[0].a("permission"), "android.permission.BIND_ACCESSIBILITY_SERVICE")


NOTICES = ("Backstage - third-party and license notices\n"
           "Corresponding source: https://example.invalid/repo at commit 0123abcd\n\n"
           "== libvosk.so ==\nComponent: Vosk\nLicense: Apache-2.0 AND BSD-3-Clause\n"
           "Texts: licenses/texts/Apache-2.0.txt, licenses/texts/OpenBLAS-BSD-3-Clause.txt\n\n"
           "== module-x.so ==\nComponent: PulseAudio module\nLicense: LGPL-2.1-or-later\nTexts: upstream/dextop/third_party/deck-audio\n\n"
           "==== Notices and license texts ====\n"
           "\n---- NOTICE.md ----\n\nnotice\n\n---- LICENSE ----\n\nGPL\n"
           "\n---- licenses/texts/Apache-2.0.txt ----\n\nApache\n"
           "\n---- licenses/texts/OpenBLAS-BSD-3-Clause.txt ----\n\nBSD\n"
           "\n---- upstream/dextop/third_party/deck-audio/COPYING ----\n\nLGPL\n")


def run_badging(launchers, label="Backstage"):
    lines = [f"package: name='{va.APP_ID}' versionCode='101039' versionName='1.0.0' platformBuildVersionName='16'",
             "minSdkVersion:'33'", "targetSdkVersion:'36'", f"application-label:'{label}'",
             f"application: label='{label}' icon='res/mipmap-anydpi-v26/bs_ic_launcher.xml'", "application-debuggable"]
    lines += [f"launchable-activity: name='{n}'  label='{l}' icon=''" for n, l in launchers]
    va.run = lambda cmd: (0, "\n".join(lines) + "\n")
    rep = va.Report()
    out = io.StringIO()
    with tempfile.TemporaryDirectory() as root, contextlib.redirect_stdout(out):
        va.check_badging(rep, "aapt2", "x.apk", False, root, "arm64-v8a")
    return rep, out.getvalue()


class VerifyBadgingLauncherTest(unittest.TestCase):
    def test_one_launcher_backstage_passes(self):
        rep, out = run_badging([va.LAUNCHER])
        self.assertEqual(rep.fails, 0, out)
        self.assertIn("PASS exactly one launcher entry: 'Backstage' = DextopLauncherActivity", out)
        self.assertIn("PASS application label is 'Backstage'", out)

    def test_second_launcher_or_old_label_fails(self):
        rep, out = run_badging([va.LAUNCHER, (va.AGENT_ACTIVITY, "Backstage")])
        self.assertIn("FAIL exactly one launcher entry", out)
        # The launcher's own label is what the home screen shows: Dextop's old "Backstage Desktop" fails.
        rep, out = run_badging([(va.LAUNCHER[0], "Backstage Desktop")])
        self.assertIn("FAIL exactly one launcher entry", out)
        rep, out = run_badging([va.LAUNCHER], label="Backstage Desktop")
        self.assertIn("FAIL application label is 'Backstage'", out)
        rep, out = run_badging([])
        self.assertIn("FAIL exactly one launcher entry", out)


class VerifyNoticesTest(unittest.TestCase):
    def apk(self, d, files):
        path = os.path.join(d, "x.apk")
        with zipfile.ZipFile(path, "w") as z:
            for name, data in files.items():
                z.writestr(name, data)
        return path

    def check(self, files, fn=None, *args):
        with tempfile.TemporaryDirectory() as d:
            rep = va.Report()
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                (fn or va.check_native_notices)(rep, self.apk(d, files), *args)
            return rep, out.getvalue()

    def test_every_native_library_listed_with_its_texts_passes(self):
        rep, out = self.check({"lib/arm64-v8a/libvosk.so": b"", "assets/deck/modules/module-x.so": b"",
                               va.NOTICES_ASSET: NOTICES})
        self.assertEqual(rep.fails, 0, out)
        self.assertIn("PASS all 2 native libraries have a notice entry", out)
        self.assertIn("INFO corresponding source: https://example.invalid/repo at 0123abcd", out)

    def test_unlisted_native_library_fails(self):
        rep, out = self.check({"lib/arm64-v8a/libvosk.so": b"", "lib/arm64-v8a/libnew.so": b"", va.NOTICES_ASSET: NOTICES})
        self.assertIn("FAIL all 2 native libraries have a notice entry: no entry for ['libnew.so']", out)

    def test_named_text_missing_from_the_asset_fails(self):
        broken = NOTICES.replace("\n---- licenses/texts/OpenBLAS-BSD-3-Clause.txt ----\n\nBSD\n", "")
        rep, out = self.check({"lib/arm64-v8a/libvosk.so": b"", va.NOTICES_ASSET: broken})
        self.assertIn("FAIL every license/notice text the entries name is included in full: missing "
                      "['licenses/texts/OpenBLAS-BSD-3-Clause.txt']", out)

    def test_missing_asset_fails_and_dirty_build_is_flagged(self):
        rep, out = self.check({"lib/arm64-v8a/libvosk.so": b""})
        self.assertIn(f"FAIL {va.NOTICES_ASSET} is packaged", out)
        rep, out = self.check({"lib/arm64-v8a/libvosk.so": b"",
                               va.NOTICES_ASSET: NOTICES.replace("0123abcd", "0123abcd-dirty")})
        self.assertEqual(rep.fails, 0, out)
        self.assertIn("FLAG built from 0123abcd-dirty source", out)

    def test_closed_source_sdk_must_be_disclosed_in_notice(self):
        dex = b"dex\nLcom/google/android/gms/cast/framework/CastContext;\n"
        with tempfile.TemporaryDirectory() as root:
            with open(os.path.join(root, "NOTICE.md"), "w") as f:
                f.write("nothing about it\n")
            rep, out = self.check({"classes.dex": dex}, va.check_closed_source_sdks, root)
            self.assertIn("FAIL closed-source Google SDK in the dex: Google Cast SDK (play-services-cast-framework) is not disclosed", out)
            with open(os.path.join(root, "NOTICE.md"), "w") as f:
                f.write("Google Cast SDK: com.google.android.gms:play-services-cast-framework (closed-source)\n")
            rep, out = self.check({"classes.dex": dex}, va.check_closed_source_sdks, root)
            self.assertEqual(rep.fails, 0, out)
            self.assertIn("FLAG closed-source Google SDK in the dex: Google Cast SDK", out)

    def test_repository_notice_discloses_every_known_closed_source_sdk(self):
        with open(os.path.join(os.path.dirname(SCRIPTS), "NOTICE.md"), encoding="utf-8") as f:
            notice = f.read()
        for _, what, name in va.CLOSED_SOURCE_SDKS:
            self.assertIn(name, notice, what)


def run_a11y_config(attrs):
    resources = "  resource 0x7f140006 xml/mirror_accessibility_service\n    () (file) res/Qx.xml type=XML\n"
    xml = "\n".join(element(1, "accessibility-service", attrs)) + "\n"
    va.run = lambda cmd: (0, resources if cmd[2] == "resources" else xml)
    rep = va.Report()
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        va.check_a11y_configs(rep, "aapt2", "x.apk", {"moe.n4tsu.dextop.MirrorService": "@0x7f140006"})
    return rep, out.getvalue()


class VerifyA11yConfigTest(unittest.TestCase):
    COMBINED = {"canRetrieveWindowContent": True, "canPerformGestures": True, "canTakeScreenshot": True,
                "isAccessibilityTool": True, "accessibilityFlags": "0x4052", "accessibilityEventTypes": "0x403879"}

    def test_combined_configuration_passes(self):
        rep, out = run_a11y_config(self.COMBINED)
        self.assertEqual(rep.fails, 0, out)
        self.assertIn("include upstream's and the agent's", out)

    def test_upstream_only_configuration_fails(self):
        # Upstream Dextop's own file: no gestures, no screenshots, no view ids, none of the agent's event types.
        rep, out = run_a11y_config({"canRetrieveWindowContent": True,
                                    "accessibilityFlags": "0x4040", "accessibilityEventTypes": "0x402039"})
        self.assertIn("FAIL MirrorService config: canRetrieveWindowContent, canPerformGestures, canTakeScreenshot, "
                      "isAccessibilityTool = true", out)
        self.assertIn("FAIL MirrorService config: accessibilityFlags 0x4040", out)
        self.assertIn("FAIL MirrorService config: accessibilityEventTypes 0x402039", out)


class VerifyCompanionTest(unittest.TestCase):
    def test_a_car_companion_apk_fails(self):
        # Backstage excludes the Car Companion from builds and distributions.
        va.run = lambda cmd: (0, f"package: name='{va.COMPANION_ID}' versionCode='8' versionName='2.1.0'\n")
        out = io.StringIO()
        argv = sys.argv
        sys.argv = ["verify_apk.py", "x.apk", "--aapt2", "aapt2", "--apksigner", "apksigner"]
        try:
            with contextlib.redirect_stdout(out):
                code = va.main()
        finally:
            sys.argv = argv
        self.assertEqual(code, 1)
        self.assertIn("FAIL com.eggman.backstage.cardex: Backstage no longer builds or ships the Car Companion", out.getvalue())


if __name__ == "__main__":
    unittest.main()


class SignerParsingTest(unittest.TestCase):
    """apksigner prints signer lines differently across build-tools releases (CI used a newer one)."""

    def test_numbered_signer_lines(self):
        out = ("Verified using v2 scheme (APK Signature Scheme v2): true\n"
               "Signer #1 certificate DN: CN=Backstage, O=TheEggmanEmpire\n"
               "Signer #1 certificate SHA-256 digest: 1cf9cbd2dc1785adc9d4eca854f53fe3fe2399fedeb3c485a1919c724f0d5ded\n")
        self.assertEqual(va.parse_signers(out),
                         [("CN=Backstage, O=TheEggmanEmpire", "1cf9cbd2dc1785adc9d4eca854f53fe3fe2399fedeb3c485a1919c724f0d5ded")])

    def test_sdk_targeted_signer_lines(self):
        out = ("Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate DN: C=US, O=Android, CN=Android Debug\n"
               "Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: AB:CD:EF\n")
        self.assertEqual(va.parse_signers(out), [("C=US, O=Android, CN=Android Debug", "abcdef")])

    def test_one_certificate_listed_per_scheme_counts_once(self):
        out = ("Signer #1 certificate DN: CN=X\nSigner #1 certificate SHA-256 digest: aa\n"
               "Signer (minSdkVersion=28, maxSdkVersion=2147483647) certificate DN: CN=X\n"
               "Signer (minSdkVersion=28, maxSdkVersion=2147483647) certificate SHA-256 digest: aa\n")
        self.assertEqual(len(va.parse_signers(out)), 1)

    def test_two_different_certificates_are_two_signers(self):
        out = ("Signer #1 certificate DN: CN=A\nSigner #1 certificate SHA-256 digest: aa\n"
               "Signer #2 certificate DN: CN=B\nSigner #2 certificate SHA-256 digest: bb\n")
        self.assertEqual([d for _, d in va.parse_signers(out)], ["aa", "bb"])

    def test_scheme_prefixed_signer_lines(self):
        # Captured from the GitHub runner's apksigner (CI run 5): one signer, listed per scheme.
        out = ("Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\nNumber of signers: 1\n"
               "V2 Signer: certificate DN: CN=Android Debug, O=Android, C=US\n"
               "V2 Signer: certificate SHA-256 digest: df844efa06854aed8b6b36e8c4b3c57d47409abfc83d1b7eaf551ada8d370f13\n"
               "V2 Signer: certificate SHA-1 digest: 4fc35a4b6b59362a61520b8d4b6f098e1d01a8d9\n"
               "V2 Signer: public key SHA-256 digest: 8ad2c2d0bf6981e62aca861f8285ba790922af0c0a4a24ef48b8b17404f2c028\n"
               "V3 Signer: certificate DN: CN=Android Debug, O=Android, C=US\n"
               "V3 Signer: certificate SHA-256 digest: df844efa06854aed8b6b36e8c4b3c57d47409abfc83d1b7eaf551ada8d370f13\n"
               "V3.1 Signer (minSdkVersion=33, maxSdkVersion=2147483647): certificate DN: CN=Android Debug, O=Android, C=US\n"
               "V3.1 Signer (minSdkVersion=33, maxSdkVersion=2147483647): certificate SHA-256 digest: df844efa06854aed8b6b36e8c4b3c57d47409abfc83d1b7eaf551ada8d370f13\n")
        self.assertEqual(va.parse_signers(out),
                         [("CN=Android Debug, O=Android, C=US", "df844efa06854aed8b6b36e8c4b3c57d47409abfc83d1b7eaf551ada8d370f13")])

    def test_source_stamp_is_not_a_signer(self):
        out = ("Signer #1 certificate DN: CN=A\nSigner #1 certificate SHA-256 digest: aa\n"
               "Source Stamp Signer certificate DN: CN=Stamp\nSource Stamp Signer certificate SHA-256 digest: bb\n")
        self.assertEqual([d for _, d in va.parse_signers(out)], ["aa"])

    def test_no_signer_lines(self):
        self.assertEqual(va.parse_signers("Verifies\n"), [])
