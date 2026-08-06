package com.bluersw.jenkins.libraries.v3

class V3ConfigException extends RuntimeException {
    V3ConfigException(String message) {
        super(message)
    }

    V3ConfigException(String message, Throwable cause) {
        super(message, cause)
    }
}
