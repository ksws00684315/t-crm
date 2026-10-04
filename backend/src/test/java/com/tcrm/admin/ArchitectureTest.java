package com.tcrm.admin;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.shared.PublicEndpoint;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class ArchitectureTest {
  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.tcrm.admin");

  @Test
  void shouldRespectLayers_whenInspectingProductionClasses() {
    noClasses()
        .that()
        .resideInAPackage("..controller..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..mapper..")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage("..service..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..controller..")
        .check(classes);
    noClasses()
        .that()
        .resideOutsideOfPackages("..model..", "..mapper..", "..service..")
        .should()
        .dependOnClassesThat()
        .haveSimpleNameEndingWith("DO")
        .check(classes);
    classes()
        .that()
        .haveSimpleNameEndingWith("DO")
        .should()
        .resideInAPackage("..model..")
        .check(classes);
  }

  @Test
  void shouldExposeOnlyApis_whenCrossingModuleBoundaries() {
    classes()
        .should(
            new ArchCondition<JavaClass>("depend on other modules only through their api") {
              @Override
              public void check(JavaClass type, ConditionEvents events) {
                String own = module(type);
                for (var dependency : type.getDirectDependenciesFromSelf()) {
                  JavaClass target = dependency.getTargetClass();
                  String other = module(target);
                  if (!own.isEmpty() && !other.isEmpty() && !own.equals(other)) {
                    boolean valid =
                        !own.equals("shared")
                            && (other.equals("shared")
                                || (target
                                        .getPackageName()
                                        .equals("com.tcrm.admin." + other + ".api")
                                    || target
                                        .getPackageName()
                                        .startsWith("com.tcrm.admin." + other + ".api.")));
                    events.add(
                        new SimpleConditionEvent(dependency, valid, dependency.getDescription()));
                  }
                }
              }
            })
        .check(classes);
    slices().matching("com.tcrm.admin.(*)..").should().beFreeOfCycles().check(classes);
  }

  private static String module(JavaClass type) {
    String prefix = "com.tcrm.admin.";
    String name = type.getPackageName();
    return name.startsWith(prefix) ? name.substring(prefix.length()).split("\\.")[0] : "";
  }

  @Test
  void shouldDeclareAccess_whenExposingEndpoints() {
    classes()
        .that()
        .areAnnotatedWith(RestController.class)
        .should(
            new ArchCondition<JavaClass>("declare roles or public access for every endpoint") {
              @Override
              public void check(JavaClass type, ConditionEvents events) {
                for (var method : type.getMethods()) {
                  if (!method.isMetaAnnotatedWith(RequestMapping.class)
                      && !method.isAnnotatedWith(RequestMapping.class)) continue;
                  boolean declared =
                      type.isAnnotatedWith(SaCheckRole.class)
                          || type.isAnnotatedWith(PublicEndpoint.class)
                          || method.isAnnotatedWith(SaCheckRole.class)
                          || method.isAnnotatedWith(PublicEndpoint.class);
                  events.add(new SimpleConditionEvent(method, declared, method.getFullName()));
                }
              }
            })
        .check(classes);
  }

  @Test
  void shouldEncapsulateState_whenInspectingFields() {
    classes()
        .should(
            new ArchCondition<JavaClass>("have no public mutable instance fields") {
              @Override
              public void check(JavaClass type, ConditionEvents events) {
                for (var field : type.getFields()) {
                  var modifiers = field.getModifiers();
                  boolean valid =
                      !modifiers.contains(JavaModifier.PUBLIC)
                          || modifiers.contains(JavaModifier.STATIC)
                          || modifiers.contains(JavaModifier.FINAL);
                  events.add(new SimpleConditionEvent(field, valid, field.getFullName()));
                }
              }
            })
        .check(classes);
  }
}
