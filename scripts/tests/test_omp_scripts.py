"""Run the shell-level OMP cache/pin checks with the normal Python script test gate."""
from pathlib import Path
import subprocess
import unittest


class OmpScriptsTest(unittest.TestCase):
    def test_shell_helpers(self):
        run = subprocess.run(['bash', str(Path(__file__).with_name('omp-selftest.sh'))],
                             capture_output=True, text=True)
        self.assertEqual(0, run.returncode, run.stdout + run.stderr)
        self.assertIn('passed', run.stdout)
