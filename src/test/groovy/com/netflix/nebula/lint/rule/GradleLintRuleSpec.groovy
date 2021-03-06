/*
 * Copyright 2015-2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.nebula.lint.rule

import com.netflix.nebula.lint.GradleViolation
import com.netflix.nebula.lint.plugin.GradleLintPlugin
import com.netflix.nebula.lint.plugin.LintRuleRegistry
import com.netflix.nebula.lint.rule.test.AbstractRuleSpec
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.gradle.api.plugins.JavaPlugin
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Unroll

class GradleLintRuleSpec extends AbstractRuleSpec {
    @Rule
    TemporaryFolder temp

    def 'visit `apply plugin`'() {
        when:
        project.buildFile << '''
            apply plugin: 'java'
        '''

        def pluginCount = 0

        runRulesAgainst(new GradleLintRule() {
            String description = 'test'

            @Override
            void visitApplyPlugin(MethodCallExpression call, String plugin) {
                pluginCount++
            }
        })

        then:
        pluginCount == 1
    }

    def 'visit `plugins`'() {
        when:
        project.buildFile << """
            plugins {
             id 'java'
            }
        """

        def pluginCount = 0

        runRulesAgainst(new GradleLintRule() {
            String description = 'test'

            @Override
            void visitGradlePlugin(MethodCallExpression call, String conf, GradlePlugin plugin) {
                pluginCount++
            }
        })

        then:
        pluginCount == 1
    }

    def 'visit `buildScript`'() {
        when:
        project.buildFile << """
            buildscript {
                repositories {
                    maven { url 'https://plugins.gradle.org/m2/' }
                }

                dependencies {
                    classpath 'com.gradle:build-scan-plugin:1.1.1'
                }
            }
        """

        def dependenciesCount = 0

        runRulesAgainst(new GradleLintRule() {
            String description = 'test'

            @Override
            void visitGradleDependency(MethodCallExpression call, String conf, GradleDependency dep) {
                dependenciesCount++
            }
        })

        then:
        dependenciesCount == 1
    }

    abstract class GradleProjectLintRule extends GradleLintRule implements GradleModelAware {}

    def 'visit `task`'() {
        when:
        project.buildFile << '''
            task(t1)
            task('t2')
            task(t3) {}
            task('t4') {}
            task t5
            task t6 {}
            task (t7,type: Wrapper)
            task ('t8',type: Wrapper)
            task t9(type: Wrapper)
            task t10(type: Wrapper) {}
            task([:], t11)
            task([type: Wrapper], t12)
            task([type: Wrapper], t13) {}
            tasks.create([name: 't14'])
            tasks.create([name: 't15']) {}
            tasks.create('t16') {}
            tasks.create('t17')
            tasks.create('t18', Wrapper) {}
            tasks.create('t19', Wrapper.class)
        '''

        def taskCount = 0
        def calls = []
        runRulesAgainst(new GradleLintRule() {
            String description = 'test'

            @Override
            void visitTask(MethodCallExpression call, String name, Map<String, String> args) {
                calls[taskCount] = [name: name, args: args]
                taskCount++
            }
        })

        then:
        taskCount == 19
        calls[0] == [name: 't1', args: [:]]
        calls[1] == [name: 't2', args: [:]]
        calls[2] == [name: 't3', args: [:]]
        calls[3] == [name: 't4', args: [:]]
        calls[4] == [name: 't5', args: [:]]
        calls[5] == [name: 't6', args: [:]]
        calls[6] == [name: 't7', args: [type: 'Wrapper']]
        calls[7] == [name: 't8', args: [type: 'Wrapper']]
        calls[8] == [name: 't9', args: [type: 'Wrapper']]
        calls[9] == [name: 't10', args: [type: 'Wrapper']]
        calls[10] == [name: 't11', args: [:]]
        calls[11] == [name: 't12', args: [type: 'Wrapper']]
        calls[12] == [name: 't13', args: [type: 'Wrapper']]
        calls[13] == [name: 't14', args: [name: 't14']]
        calls[14] == [name: 't15', args: [name: 't15']]
        calls[15] == [name: 't16', args: [:]]
        calls[16] == [name: 't17', args: [:]]
        calls[17] == [name: 't18', args: [type: 'Wrapper']]
        calls[18] == [name: 't19', args: [type: 'Wrapper']]
        calls.size() == taskCount
    }

    private class DependencyVisitingRule extends GradleLintRule {
        final String description = 'visit dependencies'
        List<GradleDependency> deps = []
        
        @Override
        void visitGradleDependency(MethodCallExpression call, String conf, GradleDependency dep) {
            deps += dep
        }
        
        DependencyVisitingRule run() { runRulesAgainst(this); this }
    }
    
    def 'visit dependencies in subprojects block'() {
        when:
        project.buildFile << """
            subprojects {
                dependencies {
                   compile 'b:b:1'
                }
            }
        """

        def b = new DependencyVisitingRule().run().deps.find { it.name == 'b' }

        then:
        b
        b.syntax == GradleDependency.Syntax.StringNotation
    }
    
    def 'visit dependencies that are defined with map notation'() {
        when:
        project.buildFile << """
            dependencies {
               compile group: 'a', name: 'a', version: '1'
            }
        """

        def a = new DependencyVisitingRule().run().deps.find { it.name == 'a' }

        then:
        a
        a.group == 'a'
        a.version == '1'
        a.syntax == GradleDependency.Syntax.MapNotation
    }

    def 'visit dependency with no version'() {
        when:
        project.buildFile << """
            dependencies {
               compile 'a:a'
            }
        """

        def a = new DependencyVisitingRule().run().deps.find { it.name == 'a' }

        then:
        a
        a.version == null
    }

    def 'add violation with deletion'() {
        when:
        project.buildFile << "apply plugin: 'java'"

        def rule = new GradleLintRule() {
            String description = 'test'

            @Override
            void visitApplyPlugin(MethodCallExpression call, String plugin) {
                addBuildLintViolation("'apply plugin' syntax is not allowed", call).delete(call)
            }
        }

        then:
        correct(rule) == ''
    }

    def 'add violation with multiple insertions'() {
        when:
        project.buildFile << """
            apply plugin: 'java'

            dependencies {
                compile 'com.google.guava:guava:18.0'
            }
        """.stripIndent()

        def rule = new GradleLintRule() {
            String description = 'test'

            @Override
            void visitApplyPlugin(MethodCallExpression call, String plugin) {
                bookmark('lastApplyPlugin', call)
            }

            @Override
            void visitGradleDependency(MethodCallExpression call, String conf, GradleDependency dep) {
                if (bookmark('lastApplyPlugin')) {
                    addBuildLintViolation('should generate source jar', call)
                            .insertAfter(bookmark('lastApplyPlugin'), "apply plugin: 'nebula.source-jar'")
                    addBuildLintViolation('should generate javadoc jar', call)
                            .insertAfter(bookmark('lastApplyPlugin'), "apply plugin: 'nebula.javadoc-jar'")
                }
            }
        }

        then:
        correct(rule) == """
            apply plugin: 'java'
            apply plugin: 'nebula.source-jar'
            apply plugin: 'nebula.javadoc-jar'

            dependencies {
                compile 'com.google.guava:guava:18.0'
            }
        """.stripIndent()
    }

    @Unroll
    def 'violations suppression inside of ignore blocks when ignored rule(s) is `#rules`'() {
        setup:
        def rule = new GradleLintRule() {
            String description = 'test'

            @Override
            void visitApplyPlugin(MethodCallExpression call, String plugin) {
                addBuildLintViolation('no plugins allowed', call)
            }
        }
        rule.ruleId = 'no-plugins-allowed'

        new File(temp.root, 'META-INF/lint-rules').mkdirs()
        def noPluginsProp = temp.newFile("META-INF/lint-rules/no-plugins-allowed.properties")
        noPluginsProp << "implementation-class=${rule.class.name}"
        LintRuleRegistry.classLoader = new URLClassLoader([temp.root.toURI().toURL()] as URL[], getClass().getClassLoader())

        when:
        project.buildFile << """
            gradleLint.ignore($rules) { apply plugin: 'java' }
        """

        def result = runRulesAgainst(rule)

        then:
        result.violates() == violates

        where:
        rules                               | violates
        /'no-plugins-allowed'/              | false
        /'other-rule'/                      | true
        /'no-plugins-allowed','other-rule'/ | false
        ''                                  | false
    }

    def 'ignore closure properly delegates'() {
        when:
        project.with {
            plugins.apply(JavaPlugin)
            plugins.apply(GradleLintPlugin)
            dependencies {
                gradleLint.ignore {
                    compile 'com.google.guava:guava:19.0'
                }
            }
        }

        then:
        project.configurations.compile.dependencies.any { it.name == 'guava' }
    }

    def 'visit extension properties'() {
        when:
        project.buildFile << """
            nebula {
                moduleOwner = 'me'
            }

            nebula.moduleOwner = 'me'

            subprojects {
                nebula {
                    moduleOwner = 'me'
                }
            }

            allprojects {
                nebula {
                    moduleOwner 'me' // sometimes this shorthand syntax is provided, notice no '='
                }
            }
        """

        def rule = new GradleLintRule() {
            String description = 'test'

            @Override
            void visitExtensionProperty(ExpressionStatement expression, String extension, String prop) {
                if (extension == 'nebula' && prop == 'moduleOwner')
                    addBuildLintViolation('moduleOwner is deprecated and should be removed', expression)
            }
        }

        def results = runRulesAgainst(rule)

        then:
        results.violations.size() == 4
    }

    def 'codenarc visit methods in a rule have access to parent closure'() {
        when:
        project.buildFile << """
            publications {
                JAR
            }
        """

        MethodCallExpression parent = null

        runRulesAgainst(new GradleLintRule() {
            String description = 'test'

            @Override
            void visitExpressionStatement(ExpressionStatement statement) {
                if (statement.expression instanceof VariableExpression)
                    parent = parentClosure()
            }
        })

        then:
        parent?.methodAsString == 'publications'
    }

    def 'format multi-line violations'() {
        when:
        project.buildFile << """
            multiline {
              'this is a multiline'
            }
        """

        def results = runRulesAgainst(new GradleLintRule() {
            String description = 'test'

            @Override
            void visitMethodCallExpression(MethodCallExpression call) {
                if (call.methodAsString == 'multiline')
                    addBuildLintViolation('this block can be deleted', call).delete(call)
            }
        })

        then:
        (results.violations[0] as GradleViolation).sourceLine == '''
            multiline {
              'this is a multiline'
            }
        '''.stripIndent().trim()
    }
}