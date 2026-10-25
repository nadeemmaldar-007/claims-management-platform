package com.apac.claims.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.List;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

import org.springframework.web.bind.annotation.RestController;

/**
 * The layers inside every feature module: {@code api}, {@code service}, {@code domain} and {@code infrastructure}
 * (specification section 3). The rules are written once per module, so a breach names the module it happens in. A
 * module that has no class in a layer yet (notification and reporting arrive later) passes that rule trivially.
 *
 * <p>Two narrow exceptions are named in ADR 0014. The request and response records live in {@code api.dto}, as the
 * specification puts them in the api layer, so a service uses the records of its own module. And the kernel
 * ({@code shared.api}: the page envelope and the exception handler) is used by every layer.
 */
@AnalyzeClasses(packages = "com.apac.claims", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerRulesTest {

    private static final String BASE = "com.apac.claims.";

    /** Modules that follow the four-layer structure. The kernel, the configuration and the security module do not. */
    static final List<String> FEATURE_MODULES = List.of("identity", "market", "claim", "policy", "notification",
            "reporting", "integration");

    @ArchTest
    static void domainClassesDependOnNoOtherLayerOfTheirModule(JavaClasses classes) {
        for (String module : FEATURE_MODULES) {
            noClasses().that().resideInAPackage(BASE + module + ".domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(BASE + module + ".service..",
                            BASE + module + ".api..", BASE + module + ".infrastructure..")
                    .allowEmptyShould(true)
                    .because("the domain of module " + module + " must not know the layers above it")
                    .check(classes);
        }
    }

    @ArchTest
    static void domainClassesUseNeitherTheWebNorTheServletApi(JavaClasses classes) {
        noClasses().that().resideInAPackage("com.apac.claims..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..",
                        "org.springframework.http..", "jakarta.servlet..")
                .because("a domain type must be usable without a web container")
                .check(classes);
    }

    @ArchTest
    static void serviceClassesDependOnNeitherInfrastructureNorOnTheApiExceptItsOwnRecords(JavaClasses classes) {
        for (String module : FEATURE_MODULES) {
            noClasses().that().resideInAPackage(BASE + module + ".service..")
                    .should().dependOnClassesThat().resideInAnyPackage(BASE + module + ".infrastructure..")
                    .allowEmptyShould(true)
                    .because("services reach adapters only through ports (ADR 0008)")
                    .check(classes);
            noClasses().that().resideInAPackage(BASE + module + ".service..")
                    .should().dependOnClassesThat(inApiButNotItsRecords(module))
                    .allowEmptyShould(true)
                    .because("services must not know controllers; only the request and response records of "
                            + "api.dto are shared with them (ADR 0014)")
                    .check(classes);
        }
    }

    @ArchTest
    static void theApiOfAModuleIsUsedByNothingOutsideItselfExceptItsRecordsAndTheKernel(JavaClasses classes) {
        for (String module : FEATURE_MODULES) {
            noClasses().that().resideOutsideOfPackage(BASE + module + ".api..")
                    .and().resideOutsideOfPackage(BASE + module + ".service..")
                    .should().dependOnClassesThat(inApiButNotItsRecords(module))
                    .allowEmptyShould(true)
                    .because("only the api layer itself, tests and (for its records) the services use api")
                    .check(classes);
        }
        noClasses().that().resideOutsideOfPackage("com.apac.claims.bootstrap..")
                .should().dependOnClassesThat().resideInAPackage("com.apac.claims.bootstrap.api..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @ArchTest
    static void everyControllerIsARestControllerInAnApiPackage(JavaClasses classes) {
        classes().that().haveSimpleNameEndingWith("Controller")
                .should().beAnnotatedWith(RestController.class)
                .andShould().resideInAPackage("..api..")
                .check(classes);
    }

    @ArchTest
    static void restControllersLiveInAnApiPackageAndNowhereElse(JavaClasses classes) {
        classes().that().areAnnotatedWith(RestController.class)
                .should().resideInAPackage("..api..")
                .check(classes);
    }

    @ArchTest
    static void controllersDependOnServicesAndNotOnRepositories(JavaClasses classes) {
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .because("controllers go through a service, which owns the transaction and the scope check")
                .check(classes);
    }

    /** A class of the api layer of the module that is not a request or response record in {@code api.dto}. */
    private static com.tngtech.archunit.base.DescribedPredicate<JavaClass> inApiButNotItsRecords(String module) {
        return new com.tngtech.archunit.base.DescribedPredicate<>("in " + module + ".api but not in " + module
                + ".api.dto") {
            @Override
            public boolean test(JavaClass javaClass) {
                String name = javaClass.getPackageName();
                String api = BASE + module + ".api";
                return name.equals(api) || (name.startsWith(api + ".") && !name.startsWith(api + ".dto"));
            }
        };
    }
}
