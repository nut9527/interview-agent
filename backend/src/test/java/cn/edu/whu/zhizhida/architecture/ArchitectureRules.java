package cn.edu.whu.zhizhida.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;

import java.util.Set;
import java.util.function.BiPredicate;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

final class ArchitectureRules {
    static final String ROOT = "cn.edu.whu.zhizhida";
    static final Set<String> MODULES = Set.of("auth", "resume", "jobs", "interview", "ai");
    static final Set<String> WORKFLOW_APIS = Set.of("AccountLifecycle", "DemoAccounts", "ResumeUserData",
            "JobUserData", "InterviewUserData");
    static final Set<String> MODEL_ADAPTER_NAMES = Set.of("resume.DefaultAiResumeService", "resume.ResumeAiConfiguration",
            "interview.DefaultInterviewAiService", "interview.InterviewAiConfiguration");
    private static final Set<String> INTERVIEW_DATA = Set.of("InterviewSession", "InterviewState", "InterviewTurn", "InterviewMode", "InterviewDifficulty", "InterviewPreparation", "FollowUp", "InterviewAgentState", "InterviewPlanningContext");
    private static final Set<String> EFFECTS = Set.of("java.lang.System", "java.lang.Runtime", "java.lang.ProcessBuilder",
            "java.lang.Thread", "java.util.UUID", "java.util.Random", "java.util.SplittableRandom",
            "java.util.Date", "java.util.Calendar", "java.util.Timer");

    private static final DescribedPredicate<JavaClass> PERSISTENCE = DescribedPredicate.describe(
            "Repository, Mapper, Entity or database infrastructure", type -> {
                String name = topLevelSimpleName(type);
                return type.getName().startsWith(ROOT + ".") && (type.getPackageName().contains(".persistence")
                        || name.endsWith("Repository") || name.endsWith("Mapper") || name.endsWith("Entity"))
                        || type.isAnnotatedWith("org.springframework.stereotype.Repository")
                        || type.isAnnotatedWith("org.apache.ibatis.annotations.Mapper")
                        || startsWithAny(type.getName(), "java.sql.", "javax.sql.", "jakarta.persistence.", "javax.persistence.",
                        "org.springframework.jdbc.", "org.springframework.data.", "org.springframework.r2dbc.",
                        "org.apache.ibatis.", "com.baomidou.", "org.hibernate.", "io.r2dbc.");
            });
    private static final DescribedPredicate<JavaClass> CORE = DescribedPredicate.describe(
            "domain rules and shared text rules", type ->
                    type.getPackageName().contains(".domain") || type.getPackageName().equals(ROOT + ".common.text")
                            );
    private static final DescribedPredicate<JavaClass> CONTROLLER = DescribedPredicate.describe(
            "HTTP controllers (including composed annotations)", type -> topLevelSimpleName(type).endsWith("Controller")
                    || type.isAnnotatedWith("org.springframework.stereotype.Controller")
                    || type.isMetaAnnotatedWith("org.springframework.stereotype.Controller"));

    static final ArchRule MODULE_BOUNDARIES = classes().should(dependenciesRespect(
            "use other modules only through API and keep workflow management in application", (from, to) -> {
                String owner = module(from), target = module(to);
                if (MODULES.contains(target) && !owner.equals(target)) {
                    if (!isApi(to)) return false;
                    if (WORKFLOW_APIS.contains(topLevelSimpleName(to)) && !owner.equals("application")) return false;
                }
                if (MODULES.contains(owner) && target.equals("application")) return false;
                return !isApi(from) || !to.getName().startsWith(ROOT + ".") || isApi(to) || target.equals("common");
            }));

    static final ArchRule NO_MODULE_CYCLES = slices().assignedFrom(new SliceAssignment() {
        @Override
        public SliceIdentifier getIdentifierOf(JavaClass type) {
            return MODULES.contains(module(type)) ? SliceIdentifier.of(module(type)) : SliceIdentifier.ignore();
        }

        @Override
        public String getDescription() {
            return "business modules and the shared model adapter";
        }
    }).should().beFreeOfCycles();

    static final ArchRule API_TYPES = classes().that().resideInAPackage(ROOT + "..api..")
            .should(new ArchCondition<>("export only interfaces, records and enums without persistence types") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    boolean valid = (type.isInterface() || type.isRecord() || type.isEnum()) && !PERSISTENCE.test(type);
                    events.add(new SimpleConditionEvent(type, valid, type.getName() + " must be an API contract"));
                }
            });

    static final ArchRule APPLICATION_PERSISTENCE = noClasses()
            .that().resideInAnyPackage(ROOT + ".application..", ROOT + "..api..")
            .should().dependOnClassesThat(PERSISTENCE);

    static final ArchRule CONTROLLER_LAYERS = noClasses().that(CONTROLLER)
            .should().dependOnClassesThat(PERSISTENCE.or(CORE))
            .because("HTTP entry points delegate database access and rule execution to application services");

    static final ArchRule CORE_INFRASTRUCTURE = noClasses().that(CORE)
            .should().dependOnClassesThat(PERSISTENCE.or(DescribedPredicate.describe(
                    "HTTP clients, security, identity or model infrastructure", type ->
                            startsWithAny(type.getName(), "java.net.", "org.springframework.web.", "org.apache.http.",
                                    "org.apache.hc.", "okhttp3.", "retrofit2.", "feign.", "org.springframework.security.",
                                    "javax.security.", "jakarta.security.", ROOT + ".auth.", ROOT + ".ai.",
                                    ROOT + ".quota.", ROOT + ".application."))));

    static final ArchRule INTERVIEW_RULES = classes().that().resideInAPackage(ROOT + ".interview.domain..")
            .should(dependenciesRespect("depend only on interview values and deterministic JDK types", (from, to) -> {
                while (to.isArray()) to = to.getComponentType();
                String name = to.getName();
                boolean jdkData = to.isPrimitive() || name.startsWith("java.lang.") || name.startsWith("java.util.")
                        && !name.startsWith("java.util.concurrent.") && !name.startsWith("java.util.random.")
                        || name.equals("java.time.Instant");
                boolean data = to.getPackageName().equals(ROOT + ".interview.api") && INTERVIEW_DATA.contains(topLevelSimpleName(to));
                return (name.startsWith(ROOT + ".interview.domain.") || data || jdkData) && !EFFECTS.contains(name);
            }));

    static final ArchRule CALCULATION_RULES = classes().that().resideInAPackage(ROOT + ".jobs.domain..")
            .should(dependenciesRespect("use only domain values, shared text rules and deterministic JDK APIs", (from, to) -> {
                while (to.isArray()) to = to.getComponentType();
                String name = to.getName();
                boolean jdk = to.isPrimitive() || startsWithAny(name, "java.lang.", "java.util.", "java.math.",
                        "java.nio.ByteBuffer", "java.nio.charset.", "java.security.MessageDigest", "java.security.NoSuchAlgorithmException");
                boolean values = isApi(to) && (to.isRecord() || to.isEnum());
                return (jdk || values || to.getPackageName().contains(".domain")
                        || to.getPackageName().equals(ROOT + ".common.text"))
                        && !EFFECTS.contains(name) && !startsWithAny(name, "java.util.concurrent.", "java.util.random.");
            }));

    static final ArchRule MODEL_ADAPTERS = classes().should(dependenciesRespect(
            "keep generic model clients in business adapters and wiring", (from, to) -> {
                String owner = module(from), target = module(to);
                if (MODULES.contains(owner) && !owner.equals("ai") && target.equals("ai")) {
                    return MODEL_ADAPTER_NAMES.contains(from.getName().substring(ROOT.length() + 1));
                }
                return !owner.equals("ai") || !MODULES.contains(target) || target.equals("ai");
            }));

    private static ArchCondition<JavaClass> dependenciesRespect(String description, BiPredicate<JavaClass, JavaClass> allowed) {
        return new ArchCondition<>(description) {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                type.getDirectDependenciesFromSelf().forEach(dependency -> {
                    if (!allowed.test(type, dependency.getTargetClass())) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                });
            }
        };
    }

    static String module(JavaClass type) {
        String name = type.getPackageName();
        return name.startsWith(ROOT + ".") ? name.substring(ROOT.length() + 1).split("\\.")[0] : "external";
    }

    private static boolean isApi(JavaClass type) {
        String apiPackage = ROOT + "." + module(type) + ".api";
        return type.getPackageName().equals(apiPackage) || type.getPackageName().startsWith(apiPackage + ".");
    }

    private static String topLevelSimpleName(JavaClass type) {
        while (type.isArray()) type = type.getComponentType();
        int start = type.getName().lastIndexOf('.') + 1;
        return type.getName().substring(start).split("\\$")[0];
    }

    private static boolean startsWithAny(String name, String... prefixes) {
        for (String prefix : prefixes) if (name.startsWith(prefix)) return true;
        return false;
    }
}
