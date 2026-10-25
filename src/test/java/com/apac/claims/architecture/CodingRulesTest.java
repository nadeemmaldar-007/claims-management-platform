package com.apac.claims.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.library.GeneralCodingRules;

/**
 * The coding conventions of the project that a machine can check: constructor injection only, no console output, one
 * logging API (SLF4J), no generic exceptions and no Java EE namespace.
 *
 * <p>The {@code javax} rule names the Java EE packages that moved to {@code jakarta} in Jakarta EE 9. The packages that
 * belong to the JDK itself ({@code javax.crypto}, {@code javax.sql}, {@code javax.net}, {@code javax.security.auth}) are
 * still {@code javax} and are used legitimately, so they are not forbidden.
 */
@AnalyzeClasses(packages = "com.apac.claims", importOptions = ImportOption.DoNotIncludeTests.class)
class CodingRulesTest {

    @ArchTest
    static void noClassUsesFieldInjection(JavaClasses classes) {
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.check(classes);
    }

    @ArchTest
    static void noClassWritesToTheStandardStreams(JavaClasses classes) {
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(classes);
    }

    @ArchTest
    static void noClassUsesJavaUtilLogging(JavaClasses classes) {
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(classes);
    }

    @ArchTest
    static void noClassThrowsAGenericException(JavaClasses classes) {
        GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS.check(classes);
    }

    @ArchTest
    static void noClassUsesAJavaEePackageThatMovedToJakarta(JavaClasses classes) {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("javax.persistence..", "javax.servlet..",
                        "javax.validation..", "javax.annotation..", "javax.inject..", "javax.transaction..",
                        "javax.ws.rs..", "javax.mail..", "javax.xml.bind..", "javax.jms..")
                .because("the project uses jakarta.* only")
                .check(classes);
    }

    @ArchTest
    static void noClassUsesLombok(JavaClasses classes) {
        noClasses().should().dependOnClassesThat().resideInAPackage("lombok..")
                .because("the project writes its accessors and constructors itself")
                .allowEmptyShould(true)
                .check(classes);
    }
}
