package io.jenkins.pipeline.sample

import com.lesfurets.jenkins.unit.MethodSignature
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import net.sf.json.JSONObject
import net.sf.json.JSONSerializer

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
        helper.registerAllowedMethod(MethodSignature.method("readJSON",Map.class),{return this.readJSON(it)})
        helper.registerAllowedMethod('pwd',[],{return './src/main/jenkins/com/bluersw/jenkins/libraries'})
    }

    @Test
    void testJsonStructure() throws Exception {
        boolean exception = false
        def library = library().name('shared-library')
                               .defaultVersion("master")
                               .allowOverride(false)
                               .implicit(false)
                               .targetPath(sharedLibs)
                               .retriever(localSource(sharedLibs))
                               .build()
        helper.registerSharedLibrary(library)
        runScript('com/bluersw/jenkins/libraries/JsonStructure.groovy')
        printCallStack()
    }

    @Test
    void testJsonVariable() throws Exception{
        boolean exception = false
        def library = library().name('shared-library')
                               .defaultVersion("master")
                               .allowOverride(false)
                               .implicit(false)
                               .targetPath(sharedLibs)
                               .retriever(localSource(sharedLibs))
                               .build()
        helper.registerSharedLibrary(library)
        runScript('com/bluersw/jenkins/libraries/JsonVariable.groovy')
        printCallStack()
        assertJobStatusSuccess()
    }

    JSONObject readJSON(Map<String,String> map){
        if(map.containsKey('file')) {
            FileInputStream fs = new FileInputStream(map['file'])
            String text = fs.getText()
            JSONObject jo = (JSONObject) JSONSerializer.toJSON(text)
            return jo
        }

        if(map.containsKey('test')){
            JSONObject jo = (JSONObject) JSONSerializer.toJSON(map['test'])
            return jo
        }
    }
}
