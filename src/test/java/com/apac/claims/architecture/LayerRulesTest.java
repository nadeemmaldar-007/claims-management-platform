package com.apac.claims.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.apac.claims.shared.security.Authz;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import jakarta.persistence.Entity;

import org.springframework.data.repository.Repository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The layers inside every feature module: {@code api}, {@code service}, {@code domain} and {@code infrastructure}
 * (specification section 3), and where the framework's building blocks may live: entities and repositories in the
 * domain, ports in the service layer with their adapters in infrastructure, schedules in infrastructure, and a security
 * annotation on every path that is not public. The rules are written once per module, so a breach names the module it
 * happens in; a module that has no class in a layer passes that rule trivially.
 *
 * <p>Three narrow exceptions are named in ADR 0014. The request and response records live in {@code api.dto}, as the
 * specification puts them in the api layer, so a service uses the records of its own module. The kernel
 * ({@code shared.api}: the page envelope and the exception handler) is used by every layer. And the composition root
 * ({@code bootstrap}) builds the request records of the services it calls (that last one is checked in
 * {@code ModuleDependencyRulesTest}).
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

    // ---- the rules of prompt 5: where entities, repositories, ports, schedules and security annotations live ---------------

    @ArchTest
    static void entitiesLiveInADomainPackageOnly(JavaClasses classes) {
        classes().that().areAnnotatedWith(Entity.class)
                .should().resideInAPackage("..domain..")
                .check(classes);
    }

    @ArchTest
    static void springDataRepositoriesLiveInADomainPackageOnly(JavaClasses classes) {
        classes().that().areAssignableTo(Repository.class)
                .should().resideInAPackage("..domain..")
                .because("repositories are the persistence ports of the domain layer (ADR 0002)")
                .check(classes);
    }

    @ArchTest
    static void portInterfacesAreNamedAndPlacedInAServicePackage(JavaClasses classes) {
        classes().that().areInterfaces().and(portName())
                .should().resideInAPackage("..service..")
                .because("a port belongs to the module that needs it and lives in its service layer (ADR 0008)")
                .check(classes);
    }

    @ArchTest
    static void portsAreImplementedOnlyByAdaptersInInfrastructureOrPaymentPackages(JavaClasses classes) {
        classes().that().areNotInterfaces().and(implementsAPort())
                .should().resideInAnyPackage("..infrastructure..", "..payment..")
                .because("only an adapter may implement a port: the policy lookup, the payment gateway, the channel")
                .check(classes);
    }

    @ArchTest
    static void scheduledMethodsLiveInAnInfrastructurePackageOnly(JavaClasses classes) {
        methods().that().areAnnotatedWith(Scheduled.class)
                .should().beDeclaredInClassesThat().resideInAPackage("..infrastructure..")
                .because("a background thread is framework wiring, not business logic")
                .check(classes);
    }

    /** The controllers whose paths are public on purpose: login and registration, and the redirect from the root. */
    static final Set<String> PUBLIC_CONTROLLERS = Set.of("AuthController", "RootRedirectController");

    private static final DescribedPredicate<JavaMethod> MAPPED_TO_A_PATH = DescribedPredicate.describe(
            "mapped to a path", method -> method.isAnnotatedWith(GetMapping.class)
                    || method.isAnnotatedWith(PostMapping.class) || method.isAnnotatedWith(PutMapping.class)
                    || method.isAnnotatedWith(DeleteMapping.class) || method.isAnnotatedWith(PatchMapping.class)
                    || method.isAnnotatedWith(RequestMapping.class));

    private static final DescribedPredicate<JavaMethod> IN_A_PROTECTED_CONTROLLER = DescribedPredicate.describe(
            "in a controller whose paths are not public", method -> method.getOwner().getPackageName().contains(".api")
                    && !PUBLIC_CONTROLLERS.contains(method.getOwner().getSimpleName()));

    @ArchTest
    static void everyMappedControllerMethodCarriesPreAuthorizeExceptTheNamedPublicOnes(JavaClasses classes) {
        methods().that(MAPPED_TO_A_PATH).and(IN_A_PROTECTED_CONTROLLER)
                .should().beAnnotatedWith(PreAuthorize.class)
                .because("a path without @PreAuthorize would be open to every logged-in user by accident; the public "
                        + "ones are listed by class name: " + PUBLIC_CONTROLLERS)
                .check(classes);
    }

    @ArchTest
    static void theMappedMethodMatcherDoesFindThePublicPathsToo(JavaClasses classes) {
        // The control of the rule above: without the list of public controllers the rule must find violations, and
        // they must be the paths of exactly those controllers.
        ArchRule everyone = methods().that(MAPPED_TO_A_PATH).and().areDeclaredInClassesThat()
                .resideInAPackage("..api..").should().beAnnotatedWith(PreAuthorize.class);

        EvaluationResult result = everyone.evaluate(classes);

        assertThat(result.hasViolation()).isTrue();
        List<String> details = result.getFailureReport().getDetails();
        assertThat(details).allSatisfy(detail -> assertThat(PUBLIC_CONTROLLERS.stream().anyMatch(detail::contains))
                .as("a violation outside the public controllers: " + detail).isTrue());
        for (String publicController : PUBLIC_CONTROLLERS) {
            assertThat(details.stream().anyMatch(detail -> detail.contains(publicController)))
                    .as("a path of " + publicController).isTrue();
        }
    }

    @ArchTest
    static void everyPreAuthorizeValueIsOneOfTheConstantsOfAuthz(JavaClasses classes) {
        Set<String> allowed = new HashSet<>();
        for (Field field : Authz.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    allowed.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        assertThat(allowed).as("the constants of Authz").isNotEmpty();

        methods().that().areAnnotatedWith(PreAuthorize.class)
                .should(new ArchCondition<JavaMethod>("use a value that is a constant of Authz") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        String value = method.getAnnotationOfType(PreAuthorize.class).value();
                        events.add(new SimpleConditionEvent(method, allowed.contains(value),
                                method.getFullName() + " uses @PreAuthorize(\"" + value + "\"), which is not a constant "
                                        + "of Authz"));
                    }
                })
                .check(classes);
    }

    /** An interface or class whose simple name ends like the name of a port: Gateway, Lookup or Channel. */
    private static DescribedPredicate<JavaClass> portName() {
        return DescribedPredicate.describe("named like a port (Gateway, Lookup or Channel)",
                javaClass -> endsLikeAPort(javaClass.getSimpleName()));
    }

    private static boolean endsLikeAPort(String simpleName) {
        return simpleName.endsWith("Gateway") || simpleName.endsWith("Lookup") || simpleName.endsWith("Channel");
    }

    /** A class that implements an interface of this project that is named like a port and lies in a service package. */
    private static DescribedPredicate<JavaClass> implementsAPort() {
        return DescribedPredicate.describe("implementing a port of this project", javaClass ->
                javaClass.getAllRawInterfaces().stream().anyMatch(port -> endsLikeAPort(port.getSimpleName())
                        && port.getPackageName().startsWith(BASE) && port.getPackageName().contains(".service")));
    }

    /** A class of the api layer of the module that is not a request or response record in {@code api.dto}. */
    private static DescribedPredicate<JavaClass> inApiButNotItsRecords(String module) {
        return new DescribedPredicate<>("in " + module + ".api but not in " + module
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
