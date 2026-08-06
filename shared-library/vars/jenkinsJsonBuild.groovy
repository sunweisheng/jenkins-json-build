import com.bluersw.jenkins.libraries.v3.V3Pipeline

/** Public V3 entry point. */
def call(Map options = [:]) {
    return new V3Pipeline(this, options).run()
}
