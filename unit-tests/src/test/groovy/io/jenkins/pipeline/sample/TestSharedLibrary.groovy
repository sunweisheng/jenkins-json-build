package io.jenkins.pipeline.sample

import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest

import static com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration.library
import static com.lesfurets.jenkins.unit.global.lib.LocalSource.localSource

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import com.lesfurets.jenkins.unit.BasePipelineTest

class TestSharedLibrary extends DeclarativePipelineTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder()

    String sharedLibs = this.class.getResource('/libs').getFile()

    @Override
    @Before
    void setUp() throws Exception {
        scriptRoots += 'src/main/jenkins'
        super.setUp()
        binding.setVariable('scm', [branch: 'master'])
        helper.registerAllowedMethod('isUnix',[],{true})
        helper.registerAllowedMethod('sh',[Map.class],{println("执行${it['script']}")})
        helper.registerAllowedMethod('emailext')
    }

    @Test
    void library_annotation() throws Exception {
        boolean exception = false
        def library = library().name('shared-library')
                               .defaultVersion("master")
                               .allowOverride(false)
                               .implicit(false)
                               .targetPath(sharedLibs)
                               .retriever(localSource(sharedLibs))
                               .build()
        helper.registerSharedLibrary(library)
        runScript('com/bluersw/jenkins/libraries/testRunWrapper.groovy')
        printCallStack()
    }
}
