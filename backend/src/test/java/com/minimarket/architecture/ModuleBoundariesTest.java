package com.minimarket.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Garante por teste as regras de dependência do §2.2 do plano: {@code domain} é Java puro, um
 * módulo não acessa {@code infrastructure} de outro e o grafo de módulos é acíclico.
 */
@AnalyzeClasses(packages = "com.minimarket", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundariesTest {

  @ArchTest
  static final ArchRule domain_depends_only_on_pure_java =
      noClasses()
          .that()
          .resideInAPackage("com.minimarket..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "jakarta.persistence..",
              "org.hibernate..",
              "io.quarkus..",
              "com.fasterxml.jackson..",
              "jakarta.ws.rs..")
          .because("§2.2: domain não importa Quarkus, JPA, Jackson nem HTTP")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule api_does_not_access_other_modules_infrastructure =
      classes()
          .that()
          .resideInAPackage("com.minimarket..api..")
          .should(accessOnlyOwnModuleInfrastructure())
          .because(
              "§2.2: um módulo não acessa infrastructure de outro; comunicação via application")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule modules_are_free_of_cycles =
      slices()
          .matching("com.minimarket.(*)..")
          .should()
          .beFreeOfCycles()
          .because("§2.2: módulos têm dependências em uma direção só")
          .allowEmptyShould(true);

  private static ArchCondition<JavaClass> accessOnlyOwnModuleInfrastructure() {
    return new ArchCondition<>("acessar somente a infrastructure do próprio módulo") {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        String module = moduleOf(origin);
        for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
          JavaClass target = dependency.getTargetClass();
          if (isInfrastructure(target) && !moduleOf(target).equals(module)) {
            events.add(
                SimpleConditionEvent.violated(
                    dependency,
                    "%s acessa infrastructure de outro módulo: %s"
                        .formatted(origin.getName(), target.getName())));
          }
        }
      }
    };
  }

  private static boolean isInfrastructure(JavaClass javaClass) {
    return javaClass.getPackageName().startsWith("com.minimarket.")
        && layerOf(javaClass).equals("infrastructure");
  }

  /** Módulo de negócio = terceiro segmento do pacote ({@code com.minimarket.<módulo>}). */
  private static String moduleOf(JavaClass javaClass) {
    String[] segments = javaClass.getPackageName().split("\\.");
    return segments.length > 2 ? segments[2] : "";
  }

  /** Camada = quarto segmento do pacote ({@code com.minimarket.<módulo>.<camada>}). */
  private static String layerOf(JavaClass javaClass) {
    String[] segments = javaClass.getPackageName().split("\\.");
    return segments.length > 3 ? segments[3] : "";
  }
}
