#!/usr/bin/env python3
"""Convert supported Jenkins Json Build V2 configuration into V3 JSON."""

from __future__ import annotations

import argparse
import json
import re
import shlex
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


RULES_PATH = Path(__file__).with_name("v2-conversion-rules.json")


@dataclass
class ConversionReport:
    source: str
    output: str | None = None
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    suggestions: list[str] = field(default_factory=list)

    @property
    def successful(self) -> bool:
        return not self.errors

    def as_dict(self) -> dict[str, Any]:
        return {
            "source": self.source,
            "output": self.output,
            "status": "converted" if self.successful else "manual_action_required",
            "errors": self.errors,
            "warnings": self.warnings,
            "suggestions": self.suggestions,
        }


class V2Converter:
    def __init__(self, rules: dict[str, Any]) -> None:
        self.rules = rules
        self.maven_pattern = re.compile(rules["mavenCommandPattern"])
        self.tool_command_patterns = {
            name: re.compile(pattern, re.IGNORECASE)
            for name, pattern in rules["toolCommandPatterns"].items()
        }
        self.image_builder_patterns = [
            (builder, definition["displayName"], re.compile(definition["pattern"], re.IGNORECASE))
            for builder, definition in rules["imageBuilderCommandPatterns"].items()
        ]
        self.helm_pattern = re.compile(rules["helmCommandPattern"], re.IGNORECASE)
        self.unsupported_command_patterns = [
            (name, re.compile(pattern, re.IGNORECASE))
            for name, pattern in rules["unsupportedCommandPatterns"].items()
        ]

    def convert(self, source: Path, destination: Path) -> ConversionReport:
        report = ConversionReport(str(source), str(destination))
        try:
            raw = json.loads(source.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            report.errors.append(f"无法读取 V2 JSON: {error}")
            return report
        if not isinstance(raw, dict):
            report.errors.append("V2 JSON 根节点必须是对象")
            return report

        output: dict[str, Any] = {
            "schemaVersion": 3,
            "variables": dict(raw.get("GlobalVariable") or {}),
            "runtimeVariables": self._runtime_variables(raw.get("RuntimeVariable"), report),
            "parameters": [],
            "agent": {"type": "static", "label": ""},
            "stages": [],
            "post": {"always": []},
        }
        metadata = set(self.rules["metadataNodes"])
        for stage_index, (stage_name, stage_value) in enumerate(raw.items(), start=1):
            if stage_name in metadata:
                continue
            if not isinstance(stage_value, dict):
                report.errors.append(f"阶段 {stage_name} 必须是对象")
                continue
            stage = {
                "id": f"stage-{stage_index}",
                "name": stage_name,
                "steps": [],
            }
            for step_name, step_value in stage_value.items():
                if not isinstance(step_value, dict):
                    report.errors.append(f"步骤 {stage_name}/{step_name} 必须是对象")
                    continue
                converted = self._step(stage_name, step_name, step_value, raw, output, report)
                stage["steps"].extend(converted)
            output["stages"].append(stage)

        if any(not stage["steps"] for stage in output["stages"]):
            empty = [stage["name"] for stage in output["stages"] if not stage["steps"]]
            report.errors.append(f"以下阶段转换后没有可运行步骤: {', '.join(empty)}")
        if report.errors:
            report.output = None
            return report

        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return report

    def _runtime_variables(self, configured: Any, report: ConversionReport) -> list[dict[str, Any]]:
        if configured is None:
            return []
        if not isinstance(configured, dict):
            report.errors.append("RuntimeVariable 必须是对象")
            return []
        result: list[dict[str, Any]] = []
        for name, value in configured.items():
            if not isinstance(value, str):
                report.errors.append(f"RuntimeVariable.{name} 不是字符串")
                continue
            if "@path[" in value:
                report.errors.append(f"RuntimeVariable.{name} 使用了 V2 @path 语法，需要人工改为 jsonPath")
                continue
            if value.startswith("https://"):
                result.append({"name": name, "source": "http", "url": value})
            elif value.startswith(("./", "/")) and Path(value).suffix:
                result.append({"name": name, "source": "file", "path": value})
            else:
                result.append({"name": name, "source": "command", "script": value, "shell": "sh"})
        return result

    def _step(
        self,
        stage_name: str,
        step_name: str,
        value: dict[str, Any],
        root: dict[str, Any],
        output: dict[str, Any],
        report: ConversionReport,
    ) -> list[dict[str, Any]]:
        step_type = str(value.get("Type") or "")
        location = f"{stage_name}/{step_name}"
        if step_type in self.rules["unsupportedTypes"]:
            report.errors.append(f"{location} 使用尚未实现的类型 {step_type}")
            return []
        if step_type in self.rules["commandTypes"]:
            return self._commands(value.get("Script"), step_name, report)

        mapping = self.rules["mappings"].get(step_type)
        if mapping == "commandLoop":
            values = [part.strip() for part in str(value.get("For") or "").split(",") if part.strip()]
            template = str(value.get("ScriptTemplate") or "")
            if not values or not template:
                report.errors.append(f"{location} 缺少 For 或 ScriptTemplate")
                return []
            return [
                {"id": f"{self._id(step_name)}-{index}", "type": "command", "shell": "sh", "script": template.replace("${loop-command-for}", item)}
                for index, item in enumerate(values, start=1)
            ]
        if mapping == "conditionalCommand":
            test_script = str(value.get("TestScript") or "")
            scripts = self._script_values(value.get("Script"), location, report)
            if not test_script or not scripts:
                return []
            expected = str(value.get("Expect") or "")
            not_expected = str(value.get("NotExpect") or "")
            operator = "=" if expected else "!="
            target = expected or not_expected
            body = "\n".join(scripts)
            shell = f"_v3_status=0\n{test_script} || _v3_status=$?\nif [ \"$_v3_status\" {operator} {shlex.quote(target)} ]; then\n{body}\nfi"
            report.warnings.append(f"{location} 已转为等价 shell 条件，请人工复核")
            return [{"id": self._id(step_name), "type": "command", "shell": "sh", "script": shell}]
        if mapping == "credentials":
            credential_id = str(value.get("CredentialsId") or "")
            commands = self._commands(value.get("Script"), step_name, report)
            if not credential_id:
                report.errors.append(f"{location} 缺少 CredentialsId")
                return []
            return [{
                "id": self._id(step_name),
                "type": "credentials",
                "bindings": [{
                    "kind": "usernamePassword",
                    "credentialsId": credential_id,
                    "usernameVariable": "username",
                    "passwordVariable": "password",
                }],
                "steps": commands,
            }]
        if mapping == "choiceParameter":
            parameter_name = str(value.get("ParamName") or "")
            target_stage = root.get(str(value.get("StepsName") or ""))
            if not parameter_name or not isinstance(target_stage, dict):
                report.errors.append(f"{location} 无法确定构建参数或候选值")
                return []
            output["parameters"].append({
                "name": parameter_name,
                "type": "choice",
                "choices": list(target_stage.keys()),
                "description": "从 V2 部署步骤转换",
            })
            return [{"id": self._id(step_name), "type": "setVariable", "name": f"{self._id(step_name)}_READY", "value": True}]
        if mapping == "junit":
            path = str(value.get("JunitReportPath") or "")
            if not path:
                report.errors.append(f"{location} 缺少 JunitReportPath")
                return []
            return [{"id": self._id(step_name), "type": "junit", "testResults": path}]
        if mapping == "jacoco":
            converted: dict[str, Any] = {"id": self._id(step_name), "type": "jacoco"}
            for old_name, new_name in self.rules["jacocoFields"].items():
                if old_name in value:
                    converted[new_name] = value[old_name]
            if value.get("FailPrompt") in {"FAILURE", "UNSTABLE"}:
                converted["changeBuildStatus"] = True
            return [converted]
        if mapping == "sonarqube":
            scanner = str(value.get("ScannerScript") or "")
            if scanner:
                shell = "powershell" if re.search(r"MSBuild(?:\.exe)?", scanner, re.IGNORECASE) else "sh"
                return [{
                    "id": self._id(step_name),
                    "type": "sonarqube",
                    "script": scanner,
                    "shell": shell,
                    "qualityGate": True,
                }]
            return [{"id": self._id(step_name), "type": "sonarqube", "goals": ["sonar:sonar"], "qualityGate": True}]
        if mapping in self.rules["coverageTypes"]:
            return self._coverage_step(mapping, step_name, value, location, report)

        report.errors.append(f"{location} 使用无法转换的类型 {step_type or '<empty>'}")
        return []

    def _commands(self, configured: Any, parent_name: str, report: ConversionReport) -> list[dict[str, Any]]:
        if not isinstance(configured, dict) or not configured:
            report.errors.append(f"{parent_name} 的 Script 必须是非空对象")
            return []
        result: list[dict[str, Any]] = []
        for command_name, command_text in configured.items():
            if not isinstance(command_text, str) or not command_text.strip():
                report.errors.append(f"{parent_name}/{command_name} 的命令为空")
                continue
            error_count = len(report.errors)
            converted = self._structured_command(command_text, command_name, f"{parent_name}/{command_name}", report)
            if converted is not None:
                result.append(converted)
                continue
            if len(report.errors) != error_count:
                continue
            unsupported = [
                stack_name
                for stack_name, pattern in self.unsupported_command_patterns
                if pattern.search(command_text)
            ]
            if unsupported:
                report.errors.append(
                    f"{parent_name}/{command_name} 无法可靠转换: {', '.join(unsupported)}"
                )
                continue
            for builder, display_name, pattern in self.image_builder_patterns:
                if pattern.search(command_text):
                    report.suggestions.append(
                        f"{parent_name}/{command_name} 检测到 {display_name} 命令，请人工改用 builder={builder} 的 containerImage 步骤"
                    )
            if self.helm_pattern.search(command_text):
                report.suggestions.append(f"{parent_name}/{command_name} 检测到 Helm 命令，请人工改用 helm 步骤")
            result.append({"id": self._id(command_name), "type": "command", "shell": "sh", "script": command_text})
        return result

    def _structured_command(
        self,
        command_text: str,
        command_name: str,
        location: str,
        report: ConversionReport,
    ) -> dict[str, Any] | None:
        match = self.maven_pattern.match(command_text.strip())
        if match:
            work_dir, executable, tail = match.groups()
            try:
                tokens = shlex.split(tail)
            except ValueError:
                tokens = []
            if tokens and all(not re.search(r"[|;&<>]", token) for token in tokens):
                goals = [token for token in tokens if not token.startswith("-")]
                arguments = [token for token in tokens if token.startswith("-")]
                converted: dict[str, Any] = {
                    "id": self._id(command_name),
                    "type": "maven",
                    "executable": executable,
                    "goals": goals,
                    "arguments": arguments,
                }
                if work_dir:
                    converted["workDir"] = work_dir.strip()
                return converted
        for tool_name, pattern in self.tool_command_patterns.items():
            match = pattern.match(command_text.strip())
            if not match:
                continue
            work_dir, executable, tail = match.groups()
            if tool_name == "npm":
                return self._npm_command(command_name, work_dir, executable, tail, location, report)
            if tool_name == "gradle":
                return self._gradle_command(command_name, work_dir, executable, tail, location, report)
            if tool_name == "msbuild":
                return self._msbuild_command(command_name, work_dir, executable, tail, location, report)
            if tool_name == "xcodebuild":
                return self._xcodebuild_command(command_name, work_dir, executable, tail, location, report)
        return None

    def _npm_command(
        self,
        name: str,
        work_dir: str | None,
        executable: str,
        tail: str,
        location: str,
        report: ConversionReport,
    ) -> dict[str, Any] | None:
        tokens = self._shell_tokens(tail, location, report)
        if not tokens:
            return None
        if self._is_probe(tokens):
            return None
        converted: dict[str, Any] = {
            "id": self._id(name),
            "type": "npm",
            "executable": executable,
            "command": tokens[0],
        }
        if len(tokens) > 1:
            converted["arguments"] = tokens[1:]
        if work_dir:
            converted["workDir"] = work_dir.strip()
        return converted

    def _gradle_command(
        self,
        name: str,
        work_dir: str | None,
        executable: str,
        tail: str,
        location: str,
        report: ConversionReport,
    ) -> dict[str, Any] | None:
        tokens = self._shell_tokens(tail, location, report)
        if not tokens:
            return None
        if self._is_probe(tokens):
            return None
        tasks = [token for token in tokens if not token.startswith("-")]
        arguments = [token for token in tokens if token.startswith("-")]
        if not tasks:
            report.errors.append(f"{location} 的 Gradle 命令没有任务")
            return None
        converted: dict[str, Any] = {
            "id": self._id(name),
            "type": "gradle",
            "executable": executable,
            "tasks": tasks,
            "arguments": arguments,
        }
        if work_dir:
            converted["workDir"] = work_dir.strip()
        return converted

    def _msbuild_command(
        self,
        name: str,
        work_dir: str | None,
        executable: str,
        tail: str,
        location: str,
        report: ConversionReport,
    ) -> dict[str, Any] | None:
        try:
            tokens = shlex.split(tail, posix=False)
        except ValueError as error:
            report.errors.append(f"{location} 的 MSBuild 参数无法解析: {error}")
            return None
        if not tokens or any(re.search(r"[|;&<>]", token) for token in tokens):
            report.errors.append(f"{location} 的 MSBuild 命令包含组合操作，需要人工拆分")
            return None
        if self._is_probe(tokens):
            return None
        project = next((token for token in tokens if not token.startswith("/")), "")
        if not project:
            report.errors.append(f"{location} 的 MSBuild 命令缺少项目或解决方案")
            return None
        targets: list[str] = []
        properties: dict[str, str] = {}
        arguments: list[str] = []
        for token in tokens:
            if token == project:
                continue
            if token.lower().startswith("/t:"):
                targets.extend(part for part in token[3:].split(";") if part)
            elif token.lower().startswith("/p:") and "=" in token[3:]:
                key, value = token[3:].split("=", 1)
                properties[key] = value
            else:
                arguments.append(token)
        converted: dict[str, Any] = {
            "id": self._id(name),
            "type": "msbuild",
            "executable": executable.strip('"'),
            "project": project.strip('"'),
        }
        if targets:
            converted["targets"] = targets
        if properties:
            converted["properties"] = properties
        if arguments:
            converted["arguments"] = arguments
        if work_dir:
            converted["workDir"] = work_dir.strip()
        return converted

    def _xcodebuild_command(
        self,
        name: str,
        work_dir: str | None,
        executable: str,
        tail: str,
        location: str,
        report: ConversionReport,
    ) -> dict[str, Any] | None:
        if re.search(r"[|;&<>]", tail):
            report.errors.append(f"{location} 的 xcodebuild 命令包含管道或多个命令，需要人工拆分并设置 xcresult")
            return None
        tokens = self._shell_tokens(tail, location, report)
        if not tokens:
            return None
        if self._is_probe(tokens):
            return None
        configured_actions = set(self.rules["xcodeActions"])
        options = {
            "-workspace": "workspace",
            "-project": "project",
            "-scheme": "scheme",
            "-configuration": "configuration",
            "-destination": "destination",
            "-derivedDataPath": "derivedDataPath",
            "-resultBundlePath": "resultBundlePath",
            "-archivePath": "archivePath",
            "-exportPath": "exportPath",
            "-exportOptionsPlist": "exportOptionsPlist",
        }
        converted: dict[str, Any] = {"id": self._id(name), "type": "xcodebuild"}
        actions: list[str] = []
        arguments: list[str] = []
        index = 0
        while index < len(tokens):
            token = tokens[index]
            if token == "-exportArchive":
                actions.append("exportArchive")
                index += 1
                continue
            if token in configured_actions:
                actions.append(token)
                index += 1
                continue
            if token == "-allowProvisioningUpdates":
                converted["allowProvisioningUpdates"] = True
                index += 1
                continue
            if token in options:
                if index + 1 >= len(tokens):
                    report.errors.append(f"{location} 的 {token} 缺少值")
                    return None
                converted[options[token]] = tokens[index + 1]
                index += 2
                continue
            arguments.append(token)
            index += 1
        if len(actions) != 1:
            report.errors.append(f"{location} 的 xcodebuild 命令必须只有一个 action")
            return None
        action = actions[0]
        converted["action"] = action
        if action == "exportArchive":
            required = ["archivePath", "exportPath", "exportOptionsPlist"]
        else:
            required = ["scheme"]
            if bool(converted.get("workspace")) == bool(converted.get("project")):
                report.warnings.append(f"{location} 未明确 workspace 或 project，已保留为普通 command")
                return None
        missing = [field for field in required if not converted.get(field)]
        if missing:
            report.warnings.append(f"{location} 缺少结构化参数 {', '.join(missing)}，已保留为普通 command")
            return None
        if arguments:
            converted["arguments"] = arguments
        if work_dir:
            converted["workDir"] = work_dir.strip()
        return converted

    def _coverage_step(
        self,
        mapping: str,
        step_name: str,
        value: dict[str, Any],
        location: str,
        report: ConversionReport,
    ) -> list[dict[str, Any]]:
        definition = self.rules["coverageTypes"][mapping]
        if mapping == "coverageLlvm":
            bundle = next((str(value.get(field)) for field in definition["resultBundleFields"] if value.get(field)), "")
            if not bundle:
                report.errors.append(f"{location} 无法从旧 LLVM 配置确定 xcresult 路径，需要人工增加 XcodeResultBundlePath")
                return []
            return [{
                "id": self._id(step_name),
                "type": "xcodeCoverage",
                "resultBundlePath": bundle,
                "outputFile": definition["outputFile"],
            }]

        pattern = next((str(value.get(field)) for field in definition["pathFields"] if value.get(field)), "")
        if not pattern:
            pattern = definition["defaultPattern"]
            report.warnings.append(f"{location} 未提供标准覆盖率 XML/LCOV 路径，已使用默认值 {pattern}，请人工确认")
        converted: dict[str, Any] = {
            "id": self._id(step_name),
            "type": "coverage",
            "reports": [{"format": definition["format"], "pattern": pattern}],
        }
        quality_gates: list[dict[str, Any]] = []
        for old_name, metric in definition.get("qualityMetrics", {}).items():
            if value.get(old_name) in (None, ""):
                continue
            try:
                threshold = float(value[old_name])
            except (TypeError, ValueError):
                report.errors.append(f"{location}.{old_name} 不是有效数字")
                continue
            quality_gates.append({
                "metric": metric,
                "baseline": "PROJECT",
                "threshold": int(threshold) if threshold.is_integer() else threshold,
                "unstable": False,
            })
        if quality_gates:
            converted["qualityGates"] = quality_gates
        return [converted]

    @staticmethod
    def _shell_tokens(tail: str, location: str, report: ConversionReport) -> list[str]:
        try:
            tokens = shlex.split(tail)
        except ValueError as error:
            report.errors.append(f"{location} 的命令参数无法解析: {error}")
            return []
        if any(re.search(r"[|;&<>]", token) for token in tokens):
            report.errors.append(f"{location} 包含组合命令，需要人工拆分")
            return []
        return tokens

    def _is_probe(self, tokens: list[str]) -> bool:
        return bool(tokens) and all(token in self.rules["probeArguments"] for token in tokens)

    @staticmethod
    def _script_values(configured: Any, location: str, report: ConversionReport) -> list[str]:
        if not isinstance(configured, dict) or not configured:
            report.errors.append(f"{location} 的 Script 必须是非空对象")
            return []
        return [str(value) for value in configured.values()]

    @staticmethod
    def _id(value: str) -> str:
        normalized = re.sub(r"[^A-Za-z0-9_.-]+", "-", value).strip("-").lower()
        return normalized or "converted-step"


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="将 Jenkins Json Build V2 JSON 转换为 V3")
    parser.add_argument("inputs", nargs="+", type=Path, help="V2 JSON 文件")
    parser.add_argument("--output-dir", required=True, type=Path, help="V3 文件输出目录")
    parser.add_argument("--report", required=True, type=Path, help="人工处理报告 JSON")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    rules = json.loads(RULES_PATH.read_text(encoding="utf-8"))
    converter = V2Converter(rules)
    reports: list[ConversionReport] = []
    for source in args.inputs:
        destination = args.output_dir / f"{source.stem}.v3.json"
        if source.resolve() == destination.resolve():
            report = ConversionReport(str(source))
            report.errors.append("输出路径不能覆盖 V2 原文件")
        else:
            report = converter.convert(source, destination)
        reports.append(report)

    args.report.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "status": "converted" if all(report.successful for report in reports) else "manual_action_required",
        "files": [report.as_dict() for report in reports],
    }
    args.report.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if payload["status"] == "converted" else 2


if __name__ == "__main__":
    raise SystemExit(main())
