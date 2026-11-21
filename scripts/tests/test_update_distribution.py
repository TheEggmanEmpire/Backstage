"""Built-APK updater boundary: installer permission, component and release-checker requirements."""
import contextlib
import io
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from test_apk_tools import va, E, element


class UpdateDistributionTest(unittest.TestCase):
    def check(self, *, installer=True, component=True, dex=True):
        manifest = E("manifest", {}, E("application", {}, *[
            E(kind, {"name": name, "exported": False, **({"foregroundServiceType": 1} if kind == "service" else {})}) for name, kind in va.UPDATE_COMPONENTS.items()
        ] if component else []))
        lines = manifest(1)
        if installer:
            lines += element(3, "uses-permission", {"name": "android.permission.REQUEST_INSTALL_PACKAGES"})
            lines += element(3, "uses-permission", {"name": "android.permission.FOREGROUND_SERVICE_DATA_SYNC"})
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"Lcom/eggman/backstage/update/GitHubReleaseClient;" if dex else b"No checker")
            rep, output = va.Report(), io.StringIO()
            with patch.object(va, "run", return_value=(0, "\n".join(lines))), contextlib.redirect_stdout(output):
                va.check_update_distribution(rep, "aapt2", str(apk))
            return rep, output.getvalue()

    def test_github_requires_installer_permission_and_updater(self):
        rep, text = self.check()
        self.assertEqual(0, rep.fails, text)
        for changes in ({"installer": False}, {"component": False}, {"dex": False}):
            rep, text = self.check(**changes)
            self.assertGreater(rep.fails, 0, text)

