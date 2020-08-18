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

    static final String UNIX_PREFIX = '#!/bin/bash -il\n '

    @Rule
    public TemporaryFolder folder = new TemporaryFolder()

    String sharedLibs = this.class.getResource('/libs').getFile()

    @Override
    @Before
    void setUp() throws Exception {
        scriptRoots += 'src/main/jenkins'
        super.setUp()
        binding.setVariable('scm', [branch: 'master'])
        binding.setVariable('JENKINS_URL', 'JENKINS_URL')
        binding.setVariable('JOB_NAME', 'JOB_NAME')
        binding.setVariable('BUILD_NUMBER', 'BUILD_NUMBER')
        binding.setVariable('WORKSPACE', './src/main/jenkins/com/bluersw/jenkins/libraries')
        helper.registerAllowedMethod('isUnix',[],{true})
        helper.registerAllowedMethod('sh',[Map.class],{sh(it)})
        helper.registerAllowedMethod('sonarQube',[],{})
        helper.registerAllowedMethod('emailext')
        helper.registerAllowedMethod('jacoco',[Map.class],{})
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
        assertJobStatusSuccess()
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

    @Test
    void testJavaBuild() throws Exception{
        boolean exception = false
        def library = library().name('shared-library')
                               .defaultVersion("master")
                               .allowOverride(false)
                               .implicit(false)
                               .targetPath(sharedLibs)
                               .retriever(localSource(sharedLibs))
                               .build()
        helper.registerSharedLibrary(library)
        runScript('com/bluersw/jenkins/libraries/JavaBuild.groovy')
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

    def sh(Map<String,String> map){
        String script = map['script']
        script = script.replace(UNIX_PREFIX,'').trim()
        def result
        switch (script) {
        case ('pwd'):
            result = '/jenkins/home'
            break
        case ('java -version 2>&1'):
            result = 'java version \"1.8.0_211\"'
            break
        case ('mvn -v'):
            result = 'Apache Maven 3.6.3'
            break
        case ('sonar-scanner -v'):
            result = 'SonarScanner 4.4.0.2170'
            break
        default:
            if(map["returnStatus"] as boolean) {
                result = 0
            }else{
                result = "run ${script}"
            }
            break
        }
        return result
    }
}
