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
                converted = self._command(scanner, step_name)
                report.suggestions.append(f"{location} 的 Sonar 命令已保留；建议人工改用 sonarqube 标准步骤")
                return [converted]
            return [{"id": self._id(step_name), "type": "sonarqube", "goals": ["sonar:sonar"], "qualityGate": True}]

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
            unsupported = [
                stack_name
                for stack_name, pattern in self.unsupported_command_patterns
                if pattern.search(command_text)
            ]
            if unsupported:
                report.errors.append(
                    f"{parent_name}/{command_name} 检测到 V3.0 尚未支持的技术栈: {', '.join(unsupported)}"
                )
                continue
            for builder, display_name, pattern in self.image_builder_patterns:
                if pattern.search(command_text):
                    report.suggestions.append(
                        f"{parent_name}/{command_name} 检测到 {display_name} 命令，请人工改用 builder={builder} 的 containerImage 步骤"
                    )
            if self.helm_pattern.search(command_text):
                report.suggestions.append(f"{parent_name}/{command_name} 检测到 Helm 命令，请人工改用 helm 步骤")
            result.append(self._command(command_text, command_name))
        return result

    def _command(self, command_text: str, command_name: str) -> dict[str, Any]:
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
        return {"id": self._id(command_name), "type": "command", "shell": "sh", "script": command_text}

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
