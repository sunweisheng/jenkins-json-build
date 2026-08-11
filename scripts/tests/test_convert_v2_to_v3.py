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

    def test_converts_multilanguage_commands_and_coverage(self):
        payload = {
            "构建": {
                "Npm": {"Type": "COMMAND_STATUS", "Script": {"install": "cd web;npm ci"}},
                "Gradle": {"Type": "COMMAND_STATUS", "Script": {"android": "cd android;./gradlew test assembleRelease --stacktrace"}},
                "MSBuild": {"Type": "COMMAND_STATUS", "Script": {"windows": "cd src && MSBuild.exe App.sln /t:Build /p:Configuration=Release"}},
                "Xcode": {"Type": "COMMAND_STATUS", "Script": {"ios": "cd ios;xcodebuild -workspace App.xcworkspace -scheme App -archivePath build/App.xcarchive archive"}},
                "Jest": {"Type": "JEST_COVERAGE_ANALYSIS", "LcovReportPath": "coverage/lcov.info", "Lines": "80"},
                "OpenCover": {"Type": "MSBUILD_COVERAGE_ANALYSIS", "OpenCoverReportPath": "coverage/opencover.xml", "Lines": "75"},
                "Xccov": {"Type": "LLVM_COV_COVERAGE_ANALYSIS", "XcodeResultBundlePath": "build/Test.xcresult"},
            }
        }
        temporary, process, source, output, report = self.run_converter(payload)
        self.addCleanup(temporary.cleanup)
        self.assertEqual(0, process.returncode)
        converted = json.loads(output.read_text(encoding="utf-8"))
        steps = converted["stages"][0]["steps"]
        self.assertEqual(
            ["npm", "gradle", "msbuild", "xcodebuild", "coverage", "coverage", "xcodeCoverage"],
            [step["type"] for step in steps],
        )
        self.assertEqual(["test", "assembleRelease"], steps[1]["tasks"])
        self.assertEqual(["Build"], steps[2]["targets"])
        self.assertEqual("archive", steps[3]["action"])
        self.assertEqual("LCOV", steps[4]["reports"][0]["format"])
        self.assertEqual("OPENCOVER", steps[5]["reports"][0]["format"])
        self.assertEqual("build/Test.xcresult", steps[6]["resultBundlePath"])
        self.assertEqual("converted", json.loads(report.read_text(encoding="utf-8"))["status"])

    def test_unreliable_commands_write_manual_report_without_v3_file(self):
        payload = {
            "构建": {
                "DotNet": {"Type": "COMMAND_STATUS", "Script": {"dotnet": "dotnet test"}},
                "iOS": {"Type": "COMMAND_STATUS", "Script": {"ios": "xcodebuild -workspace App.xcworkspace -scheme App test | xcpretty"}},
                "LLVM": {"Type": "LLVM_COV_COVERAGE_ANALYSIS"},
            },
        }
        temporary, process, source, output, report = self.run_converter(payload)
        self.addCleanup(temporary.cleanup)
        self.assertEqual(2, process.returncode)
        self.assertFalse(output.exists())
        result = json.loads(report.read_text(encoding="utf-8"))
        errors = "\n".join(result["files"][0]["errors"])
        self.assertIn("modern .NET", errors)
        self.assertIn("管道", errors)
        self.assertIn("xcresult", errors)

    def test_coverage_defaults_are_converted_with_warning(self):
        payload = {"单元测试": {"Jest": {"Type": "JEST_COVERAGE_ANALYSIS", "Lines": "90"}}}
        temporary, process, source, output, report = self.run_converter(payload)
        self.addCleanup(temporary.cleanup)
        self.assertEqual(0, process.returncode)
        converted = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual("coverage/lcov.info", converted["stages"][0]["steps"][0]["reports"][0]["pattern"])
        result = json.loads(report.read_text(encoding="utf-8"))
        self.assertTrue(result["files"][0]["warnings"])

    def test_reports_registered_image_builder_commands(self):
        payload = {
            "构建": {
                "Kaniko": {"Type": "COMMAND_STATUS", "Script": {"kaniko": "/kaniko/executor --context ."}},
                "Buildctl": {"Type": "COMMAND_STATUS", "Script": {"buildctl": "buildctl build --local context=."}},
                "Buildx": {"Type": "COMMAND_STATUS", "Script": {"buildx": "docker buildx build --push ."}},
            }
        }
        temporary, process, source, output, report = self.run_converter(payload)
        self.addCleanup(temporary.cleanup)
        self.assertEqual(0, process.returncode)
        suggestions = "\n".join(json.loads(report.read_text(encoding="utf-8"))["files"][0]["suggestions"])
        self.assertIn("builder=kaniko", suggestions)
        self.assertEqual(2, suggestions.count("builder=buildkit"))


if __name__ == "__main__":
    unittest.main()
