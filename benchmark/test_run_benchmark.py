"""Regression tests for publication; JVM/time stubs do not generate performance data."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class BenchmarkPublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shutil.copy(Path(__file__).resolve().parents[1] / "run_benchmark.sh", self.root)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.write_executable(self.root / "gradlew", '#!/bin/bash\nexit "${GRADLE_STATUS:-0}"\n')
        self.write_executable(self.bin / "fake-time", '''#!/bin/bash
if [ "$2" = true ]; then exit "${TIME_STATUS:-0}"; fi
shift
"$@"
status=$?
echo 'Maximum resident set size (kbytes): 100' >&2
exit "$status"
''')
        self.write_executable(self.bin / "java", '''#!/bin/bash
if [ "$1" = -version ]; then echo 'test JVM' >&2; exit 0; fi
case "$3" in
  benchmark.DatasetFiles) printf 'sample\tfunctional.ofn\tstandard.oprt\tmis.oprt\n' ;;
  benchmark.BenchmarkTask)
    if [ "${MEASURE_STATUS:-0}" != 0 ]; then exit "$MEASURE_STATUS"; fi
    output=0; [ "$4" = render ] && output=1
    printf '%s,%s,sample,1.0,1,%s\n' "$4" "$5" "$output" ;;
  benchmark.BenchmarkResults) exit "${VALIDATION_STATUS:-0}" ;;
  *) exit 2 ;;
esac
''')
        self.write_executable(self.bin / "git", "#!/bin/bash\necho test-revision\n")
        self.write_executable(self.bin / "sysctl", "#!/bin/bash\necho test-hardware\n")
        for name in ["functional.ofn", "standard.oprt", "mis.oprt"]:
            (self.root / name).write_text("x")
        self.previous = {"benchmark_results.csv": "previous measurements\n",
                         "benchmark_environment.txt": "previous environment\n"}
        for name, contents in self.previous.items():
            (self.root / name).write_text(contents)

    @staticmethod
    def write_executable(path, contents):
        path.write_text(contents)
        path.chmod(0o755)

    def run_script(self, **overrides):
        env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                   TIME_CMD=str(self.bin / "fake-time"))
        env.pop("ENVIRONMENT_REPORT", None)
        env.pop("BENCH_JAVA_OPTS", None)
        env.update(overrides)
        return subprocess.run(["bash", "run_benchmark.sh"], cwd=self.root, env=env,
                              capture_output=True, text=True)

    def assert_previous_preserved(self, **overrides):
        result = self.run_script(**overrides)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        for name, contents in self.previous.items():
            self.assertEqual((self.root / name).read_text(), contents)
        self.assertEqual(list(self.root.glob("benchmark_results.csv.*")), [])
        self.assertEqual(list(self.root.glob("benchmark_environment.txt.*")), [])

    def test_missing_gnu_time_preserves_results(self):
        self.assert_previous_preserved(TIME_STATUS="1")

    def test_failed_prerequisites_preserve_results(self):
        self.assert_previous_preserved(GRADLE_STATUS="1")

    def test_failed_measurement_preserves_results(self):
        self.assert_previous_preserved(MEASURE_STATUS="1")

    def test_failed_validation_preserves_results(self):
        self.assert_previous_preserved(VALIDATION_STATUS="1")

    def test_success_publishes_csv_and_environment(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        lines = (self.root / "benchmark_results.csv").read_text().splitlines()
        self.assertEqual(len(lines), 6)
        self.assertTrue(lines[0].startswith("Task,Format,Ontology,"))
        self.assertIn("test JVM", (self.root / "benchmark_environment.txt").read_text())
        self.assertEqual(list(self.root.glob("benchmark_results.csv.*")), [])


if __name__ == "__main__":
    unittest.main()
