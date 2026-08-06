import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "convert_v2_to_v3.py"


class ConvertV2ToV3Test(unittest.TestCase):
    def run_converter(self, payload):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        source = root / "v2.json"
        output = root / "output"
        report = root / "report.json"
        source.write_text(json.dumps(payload), encoding="utf-8")
        process = subprocess.run(
            [sys.executable, str(SCRIPT), str(source), "--output-dir", str(output), "--report", str(report)],
            text=True,
            capture_output=True,
            check=False,
        )
        return temporary, process, source, output / "v2.v3.json", report

    def test_converts_java_and_common_steps(self):
        payload = {
            "GlobalVariable": {"JAVA_VERSION": "21"},
            "单元测试": {
                "Maven": {"Type": "COMMAND_STATUS", "Script": {"test": "cd service;mvn clean test"}},
                "JUnit": {"Type": "JUNIT_PLUG_IN", "JunitReportPath": "**/TEST-*.xml"},
                "Jacoco": {"Type": "JACOCO_PLUG_IN", "LineCoverage": "90"},
            },
            "代码检查": {"Sonar": {"Type": "SONAR_QUBE"}},
        }
        temporary, process, source, output, report = self.run_converter(payload)
        self.addCleanup(temporary.cleanup)
        self.assertEqual(0, process.returncode)
        converted = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(3, converted["schemaVersion"])
        self.assertEqual("maven", converted["stages"][0]["steps"][0]["type"])
        self.assertEqual("junit", converted["stages"][0]["steps"][1]["type"])
        self.assertEqual("sonarqube", converted["stages"][1]["steps"][0]["type"])
        self.assertTrue(source.exists())
        self.assertEqual("converted", json.loads(report.read_text(encoding="utf-8"))["status"])

    def test_unsupported_stack_writes_report_without_v3_file(self):
        payload = {"单元测试": {"Jest": {"Type": "JEST_COVERAGE_ANALYSIS"}}}
        temporary, process, source, output, report = self.run_converter(payload)
        self.addCleanup(temporary.cleanup)
        self.assertEqual(2, process.returncode)
        self.assertFalse(output.exists())
        self.assertTrue(source.exists())
        result = json.loads(report.read_text(encoding="utf-8"))
        self.assertEqual("manual_action_required", result["status"])
        self.assertIn("JEST_COVERAGE_ANALYSIS", result["files"][0]["errors"][0])

    def test_unsupported_stack_commands_do_not_become_generic_v3_commands(self):
        payload = {
            "构建": {
                "Node": {"Type": "COMMAND_STATUS", "Script": {"node": "npm test"}},
                "DotNet": {"Type": "COMMAND_STATUS", "Script": {"dotnet": "dotnet test"}},
                "Android": {"Type": "COMMAND_STATUS", "Script": {"android": "./gradlew assembleRelease"}},
                "iOS": {"Type": "COMMAND_STATUS", "Script": {"ios": "xcodebuild archive"}},
                "ReactNative": {"Type": "COMMAND_STATUS", "Script": {"rn": "react-native bundle"}},
                "LLVM": {"Type": "COMMAND_STATUS", "Script": {"llvm": "llvm-cov show"}},
            }
        }
        temporary, process, source, output, report = self.run_converter(payload)
        self.addCleanup(temporary.cleanup)
        self.assertEqual(2, process.returncode)
        self.assertFalse(output.exists())
        result = json.loads(report.read_text(encoding="utf-8"))
        errors = "\n".join(result["files"][0]["errors"])
        for stack in ["Node", ".NET", "Android", "iOS", "React Native", "LLVM"]:
            self.assertIn(stack, errors)


if __name__ == "__main__":
    unittest.main()
