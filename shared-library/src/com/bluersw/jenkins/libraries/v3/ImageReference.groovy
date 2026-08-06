package com.bluersw.jenkins.libraries.v3

import java.util.regex.Pattern

class ImageReference implements Serializable {
    private static final Pattern DIGEST = Pattern.compile(/^sha256:[a-fA-F0-9]{64}$/)

    static String requireDigest(String value) {
        String digest = value?.trim()
        if (!digest || !DIGEST.matcher(digest).matches()) {
            throw new V3ConfigException('Kaniko 未生成有效的 sha256 镜像摘要')
        }
        return digest.toLowerCase(Locale.ENGLISH)
    }

    static String withDigest(String reference, String digest) {
        String checkedDigest = requireDigest(digest)
        String value = reference?.trim()
        if (!value) {
            throw new V3ConfigException('镜像地址不能为空')
        }
        int slash = value.lastIndexOf('/')
        int colon = value.lastIndexOf(':')
        String repository = colon > slash ? value.substring(0, colon) : value
        return "${repository}@${checkedDigest}"
    }
}
