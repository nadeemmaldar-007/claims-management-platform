package com.apac.claims.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;


/**
 * The module rules of the specification (section 3), driven by its table of allowed dependencies. For every module the
 * test computes the other modules its classes really depend on and compares them with what the table allows, so a new
 * dependency fails the build with the class, the target and the module pair named. The table is copied here as data:
 * changing an allowed dependency is a visible change to this file and to the specification together.
 *
 * <p>The kernel package {@code shared} is public to everyone, including its {@code shared.api} types (the page
 * envelope and the exception handler), which is why the rule about other modules' {@code api} packages exempts it. See
 * ADR 0014.
 */
@AnalyzeClasses(packages = "com.apac.claims", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleDependencyRulesTest {

    static final String BASE = "com.apac.claims";

    /** Section 3 of the specification, including the identity to market amendment. */
    static final Map<String, Set<String>> ALLOWED = allowedDependencies();

    private static Map<String, Set<String>> allowedDependencies() {
        Map<String, Set<String>> table = new LinkedHashMap<>();
        table.put("shared", Set.of());
        table.put("config", Set.of("shared"));
        table.put("security", Set.of("shared", "config"));
        table.put("identity", Set.of("shared", "config", "security", "market"));
        table.put("market", Set.of("shared", "config"));
        table.put("claim", Set.of("shared", "config", "identity", "market"));
        table.put("policy", Set.of("shared", "config", "identity", "market", "claim"));
        table.put("notification", Set.of("shared", "config", "identity"));
        table.put("reporting", Set.of("shared", "config", "identity", "market", "claim"));
        table.put("integration", Set.of("shared", "config", "claim"));
        table.put("bootstrap", Set.of("shared", "config", "security", "identity", "market", "claim", "policy",
                "notification", "reporting", "integration"));
        return table;
    }

    /** The module of a class: the first package below the base package, or null for the main application class. */
    static String moduleOf(JavaClass javaClass) {
        String name = javaClass.getPackageName();
        if (name.equals(BASE) || !name.startsWith(BASE + ".")) {
            return null;
        }
        String rest = name.substring(BASE.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    @ArchTest
    static void everyPackageBelowTheBaseIsAModuleOfTheSpecification(JavaClasses classes) {
        Set<String> found = new TreeSet<>();
        classes.forEach(javaClass -> {
            String module = moduleOf(javaClass);
            if (module != null) {
                found.add(module);
            }
        });

        assertThat(found).as("modules found in the code").isSubsetOf(ALLOWED.keySet());
    }

    @ArchTest
    static void everyModuleDependsOnlyOnTheModulesTheSpecificationAllows(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass origin : classes) {
            String from = moduleOf(origin);
            if (from == null) {
                continue;
            }
            Set<String> allowed = ALLOWED.getOrDefault(from, Set.of());
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String to = moduleOf(dependency.getTargetClass());
                // JDK and third-party classes have no module; the main application class has none either.
                if (to == null || to.equals(from) || allowed.contains(to)) {
                    continue;
                }
                violations.add(origin.getName() + " -> " + dependency.getTargetClass().getName()
                        + "  (module " + from + " -> " + to + " is not allowed)");
            }
        }

        assertThat(violations).as("dependencies the specification does not allow").isEmpty();
    }

    @ArchTest
    static void theAllowedDependencyTableHasNoCycle(JavaClasses classes) {
        // The table itself, independent of the code: following allowed edges never leads back to the start.
        for (String start : ALLOWED.keySet()) {
            Set<String> seen = new TreeSet<>();
            List<String> open = new ArrayList<>(ALLOWED.get(start));
            while (!open.isEmpty()) {
                String next = open.remove(open.size() - 1);
                assertThat(next).as("cycle through %s", start).isNotEqualTo(start);
                if (seen.add(next)) {
                    open.addAll(ALLOWED.getOrDefault(next, Set.of()));
                }
            }
        }
    }

    @ArchTest
    static void theModulesAreFreeOfCycles(JavaClasses classes) {
        slices().matching("com.apac.claims.(*)..").should().beFreeOfCycles().check(classes);
    }

    @ArchTest
    static void claimDependsOnNeitherPolicyNorNotificationNorReportingNorIntegration(JavaClasses classes) {
        noClasses().that().resideInAPackage("com.apac.claims.claim..")
                .should().dependOnClassesThat().resideInAnyPackage("com.apac.claims.policy..",
                        "com.apac.claims.notification..", "com.apac.claims.reporting..",
                        "com.apac.claims.integration..")
                .check(classes);
    }

    @ArchTest
    static void securityDependsOnNoFeatureModule(JavaClasses classes) {
        noClasses().that().resideInAPackage("com.apac.claims.security..")
                .should().dependOnClassesThat().resideInAnyPackage("com.apac.claims.identity..",
                        "com.apac.claims.market..", "com.apac.claims.claim..", "com.apac.claims.policy..",
                        "com.apac.claims.notification..", "com.apac.claims.reporting..",
                        "com.apac.claims.integration..", "com.apac.claims.bootstrap..")
                .check(classes);
    }

    @ArchTest
    static void sharedDependsOnNoOtherModule(JavaClasses classes) {
        noClasses().that().resideInAPackage("com.apac.claims.shared..")
                .should().dependOnClassesThat().resideInAnyPackage("com.apac.claims.config..",
                        "com.apac.claims.security..", "com.apac.claims.identity..", "com.apac.claims.market..",
                        "com.apac.claims.claim..", "com.apac.claims.policy..", "com.apac.claims.notification..",
                        "com.apac.claims.reporting..", "com.apac.claims.integration..",
                        "com.apac.claims.bootstrap..")
                .check(classes);
    }

    @ArchTest
    static void nobodyDependsOnBootstrap(JavaClasses classes) {
        noClasses().that().resideOutsideOfPackage("com.apac.claims.bootstrap..")
                .should().dependOnClassesThat().resideInAPackage("com.apac.claims.bootstrap..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @ArchTest
    static void noModuleUsesTheApiOrInfrastructurePackageOfAnotherModule(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass origin : classes) {
            String from = moduleOf(origin);
            if (from == null) {
                continue;
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                String to = moduleOf(target);
                // The kernel is public to everyone, shared.api included (ADR 0014).
                if (to == null || to.equals(from) || to.equals("shared")) {
                    continue;
                }
                String targetPackage = target.getPackageName();
                String layer = targetPackage.substring((BASE + "." + to).length());
                // The composition root builds the request records of the services it calls (ADR 0014); nothing else.
                if (from.equals("bootstrap") && (layer.equals(".api.dto") || layer.startsWith(".api.dto."))) {
                    continue;
                }
                if (layer.equals(".api") || layer.startsWith(".api.") || layer.equals(".infrastructure")
                        || layer.startsWith(".infrastructure.")) {
                    violations.add(origin.getName() + " -> " + target.getName() + "  (module " + from + " uses the "
                            + layer.substring(1).split("\\.")[0] + " package of module " + to + ")");
                }
            }
        }

        assertThat(violations).as("uses of another module's api or infrastructure package").isEmpty();
    }

    @ArchTest
    static void theModulesThatHaveCodeAreTheOnesTheRulesLookAt(JavaClasses classes) {
        // Guards the rules above against a mistake in a package name that would make them pass on an empty set.
        for (String module : List.of("shared", "config", "security", "identity", "market", "claim", "policy",
                "integration", "bootstrap")) {
            assertThat(classes.stream().filter(javaClass -> module.equals(moduleOf(javaClass))).count())
                    .as("classes of module %s", module).isPositive();
        }
    }
}
