package com.bluersw.jenkins.libraries.v3

class ShellEscaper implements Serializable {
    static String posix(Object value) {
        String text = value == null ? '' : value.toString()
        return "'${text.replace("'", "'\"'\"'")}'"
    }

    static String powershell(Object value) {
        String text = value == null ? '' : value.toString()
        return "'${text.replace("'", "''")}'"
    }
}
