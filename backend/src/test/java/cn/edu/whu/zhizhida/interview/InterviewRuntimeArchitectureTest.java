package cn.edu.whu.zhizhida.interview;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class InterviewRuntimeArchitectureTest {
    @Test void storageAndSubjectAccessCannotDependOnDispatchOrCommandServices() {
        var classes = new ClassFileImporter().importPackages("cn.edu.whu.zhizhida");
        noClasses().that().haveSimpleName("InterviewRunStore").should().dependOnClassesThat()
                .haveSimpleName("RunExecutorRegistry").check(classes);
        noClasses().that().haveSimpleName("GoalRunSubject").should().dependOnClassesThat()
                .haveSimpleName("ExecutionPort").check(classes);
        noClasses().that().haveSimpleName("GoalRunSubject").should().dependOnClassesThat()
                .haveSimpleName("LearningGoalService").check(classes);
        noClasses().that().haveSimpleName("InterviewRunModel").should().dependOnClassesThat()
                .haveSimpleName("DefaultInterviewAiService").check(classes);
        noClasses().that().haveNameMatching(".*ToolRegistry(\\$.*)?").should().dependOnClassesThat()
                .resideInAnyPackage("..interview..", "..learning..", "..knowledge..")
                .check(classes);
    }
}
