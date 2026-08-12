package com.bluersw.jenkins.libraries.v3

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class XcodeCoverageConverter implements Serializable {
    static String toCobertura(Map report, Collection<String> includedTargets = [], Collection<String> excludedPatterns = []) {
        Set<String> includes = new LinkedHashSet<String>((includedTargets ?: []).collect { it.toString() })
        List<Pattern> excludes
        try {
            excludes = (excludedPatterns ?: []).collect { Pattern.compile(it.toString()) }
        } catch (PatternSyntaxException error) {
            throw new V3ConfigException("xcodeCoverage.excludePatterns 包含无效正则表达式: ${error.message}", error)
        }

        List<Map> packages = []
        long totalCovered = 0
        long totalExecutable = 0
        for (Object targetValue : report.targets ?: []) {
            if (!(targetValue instanceof Map)) continue
            Map target = targetValue as Map
            String targetName = target.name?.toString() ?: 'XcodeTarget'
            if (!includes.isEmpty() && !includes.contains(targetName)) continue
            List<Map> classes = []
            for (Object fileValue : target.files ?: []) {
                if (!(fileValue instanceof Map)) continue
                Map file = fileValue as Map
                String path = file.path?.toString() ?: file.name?.toString()
                if (!path || excludes.any { it.matcher(path).find() }) continue
                long executable = nonNegativeLong(file.executableLines, "${targetName}/${path}.executableLines")
                long covered = nonNegativeLong(file.coveredLines, "${targetName}/${path}.coveredLines")
                if (covered > executable) throw new V3ConfigException("${targetName}/${path} 的 coveredLines 不能大于 executableLines")
                if (executable == 0) continue
                classes.add([name: file.name?.toString() ?: path.tokenize('/\\').last(), path: path,
                    covered: covered, executable: executable])
                totalCovered += covered
                totalExecutable += executable
            }
            if (!classes.isEmpty()) packages.add([name: targetName, classes: classes])
        }
        if (totalExecutable == 0) throw new V3ConfigException('xcodeCoverage 没有找到可发布的覆盖率文件')

        StringBuilder xml = new StringBuilder()
        xml.append('<?xml version="1.0" encoding="UTF-8"?>\n')
        xml.append('<coverage line-rate="').append(rate(totalCovered, totalExecutable))
            .append('" branch-rate="0" lines-covered="').append(totalCovered)
            .append('" lines-valid="').append(totalExecutable)
            .append('" branches-covered="0" branches-valid="0" complexity="0" version="xccov" timestamp="0">\n')
        xml.append('  <sources/>\n  <packages>\n')
        for (Map packageValue : packages) {
            long packageCovered = (packageValue.classes as List<Map>).sum { it.covered as long } as long
            long packageExecutable = (packageValue.classes as List<Map>).sum { it.executable as long } as long
            xml.append('    <package name="').append(escape(packageValue.name)).append('" line-rate="')
                .append(rate(packageCovered, packageExecutable)).append('" branch-rate="0" complexity="0">\n')
            xml.append('      <classes>\n')
            for (Map classValue : packageValue.classes as List<Map>) {
                xml.append('        <class name="').append(escape(classValue.name)).append('" filename="')
                    .append(escape(classValue.path)).append('" line-rate="')
                    .append(rate(classValue.covered as long, classValue.executable as long))
                    .append('" branch-rate="0" complexity="0">\n')
                xml.append('          <methods/>\n          <lines>\n')
                for (long line = 1; line <= (classValue.executable as long); line++) {
                    xml.append('            <line number="').append(line).append('" hits="')
                        .append(line <= (classValue.covered as long) ? '1' : '0').append('" branch="false"/>\n')
                }
                xml.append('          </lines>\n        </class>\n')
            }
            xml.append('      </classes>\n    </package>\n')
        }
        xml.append('  </packages>\n</coverage>\n')
        return xml.toString()
    }

    private static long nonNegativeLong(Object value, String location) {
        try {
            long result = value.toString().toLong()
            if (result < 0) throw new NumberFormatException()
            return result
        } catch (Exception ignored) {
            throw new V3ConfigException("${location} 必须是非负整数")
        }
    }

    private static String rate(long covered, long executable) {
        if (executable == 0) return '0.0'
        return BigDecimal.valueOf(covered).divide(BigDecimal.valueOf(executable), 6, BigDecimal.ROUND_HALF_UP)
            .stripTrailingZeros().toPlainString()
    }

    private static String escape(Object value) {
        return value?.toString()?.replace('&', '&amp;')?.replace('<', '&lt;')?.replace('>', '&gt;')
            ?.replace('"', '&quot;')?.replace("'", '&apos;') ?: ''
    }
}
