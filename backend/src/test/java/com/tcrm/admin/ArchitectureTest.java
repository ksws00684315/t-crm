package com.tcrm.admin;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.security.api.Edition;
import com.tcrm.admin.security.api.StpKolUtil;
import com.tcrm.admin.security.config.EditionInterceptor;
import com.tcrm.admin.shared.DirectPublishFixture;
import com.tcrm.admin.shared.PublicEndpoint;
import com.tcrm.admin.shared.TransactionalEvents;
import com.tcrm.fixtures.UndeclaredEndpointFixture;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class ArchitectureTest {
  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.tcrm.admin");

  @Test
  void shouldNotUseJdbcFromControllersOrPrepayModules() {
    noClasses()
        .that()
        .resideInAPackage("..controller..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework.jdbc..")
        .because(
            "controllers must not access data (standard 01 §3); move the query into a service"
                + " that calls a MyBatis-Plus mapper")
        .check(classes);
    noClasses()
        .that()
        .resideInAnyPackage("com.tcrm.admin.commission..", "com.tcrm.admin.notification..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(JdbcTemplate.class.getName())
        .because(
            "hand-written JdbcTemplate SQL bypasses the MyBatis-Plus tenant filter (standard 06"
                + " §2, task 47); use a mapper in the module's mapper package, with annotation or"
                + " XML SQL for row locks and ON CONFLICT")
        .check(classes);
  }

  @Test
  void shouldRespectLayers_whenInspectingProductionClasses() {
    noClasses()
        .that()
        .resideInAPackage("..controller..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..mapper..")
        .because(
            "all data access goes through the service layer (standard 01 §3); call a service"
                + " method instead of the mapper")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage("..service..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..controller..")
        .because(
            "the controller converts *Request / *Query into service parameters (standard 01 §3);"
                + " give the service its own *Command or DTO type")
        .check(classes);
    noClasses()
        .that()
        .resideOutsideOfPackages("..model..", "..mapper..", "..service..")
        .should()
        .dependOnClassesThat()
        .haveSimpleNameEndingWith("DO")
        .because(
            "*DO must not leave the module's model/mapper/service layers (standard 01 §4); return"
                + " a *VO from controllers and a *DTO from api facades")
        .check(classes);
    classes()
        .that()
        .haveSimpleNameEndingWith("DO")
        .should()
        .resideInAPackage("..model..")
        .because("*DO entities live in the module's model package (standard 01 §3, §4)")
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
        .because(
            "modules depend on each other only through com.tcrm.admin.<module>.api, and shared"
                + " depends on no business module (standard 01 §3); add a facade method or DTO to"
                + " the target module's api package")
        .check(classes);
    slices()
        .matching("com.tcrm.admin.(*)..")
        .should()
        .beFreeOfCycles()
        .because(
            "module cycles are forbidden (standard 01 §3); notify the other direction with an"
                + " application event published through shared.TransactionalEvents")
        .check(classes);
  }

  private static String module(JavaClass type) {
    String prefix = "com.tcrm.admin.";
    String name = type.getPackageName();
    return name.startsWith(prefix) ? name.substring(prefix.length()).split("\\.")[0] : "";
  }

  /**
   * Controllers open to the creator-library edition (ADR-038 §2.2, task 143 §2.3.2). Every entry
   * here is a deliberate decision; anything else belongs to the distribution product only.
   */
  private static final Set<String> KOL_LIBRARY_CONTROLLERS =
      Set.of(
          "com.tcrm.admin.security.controller.AuthController",
          "com.tcrm.admin.system.controller.UserController",
          "com.tcrm.admin.system.controller.PermissionController",
          "com.tcrm.admin.kollibrary.controller.KolLibraryController",
          "com.tcrm.admin.kollibrary.controller.KolLibraryContentController");

  /**
   * Each {@code /admin-api/**} controller belongs to a known set of editions (undeclared means
   * distribution only), and the ones open to the creator-library edition are exactly the reviewed
   * list above: opening another controller has to change this test, so it cannot happen by
   * forgetting an annotation (ADR-038 §2.3, task 143 §2.3.6).
   */
  @Test
  void shouldKeepKolLibraryControllers_onTheReviewedList() {
    Set<String> open = new TreeSet<>();
    for (JavaClass type : classes) {
      if (!type.isAnnotatedWith(RestController.class)) {
        continue;
      }
      List<Edition> editions = EditionInterceptor.allowedEditions(type.reflect());
      assertThat(editions).as("%s must declare at least one edition", type.getName()).isNotEmpty();
      if (editions.contains(Edition.KOL_LIBRARY)) {
        open.add(type.getName());
      }
    }
    assertThat(open)
        .as(
            "a controller newly open to KOL_LIBRARY must be reviewed and listed here (ADR-038"
                + " §2.3)")
        .containsExactlyInAnyOrderElementsOf(KOL_LIBRARY_CONTROLLERS);
  }

  /** The edition check runs on company endpoints only; platform and external calls are excluded. */
  @Test
  void shouldSkipEditionCheck_onlyForPathsOutsideACompanySession() {
    for (String path : EditionInterceptor.EXCLUDED) {
      assertThat(EditionInterceptor.requiresCompanyEdition(path.replace("/**", "/x")))
          .as("%s is not a company endpoint", path)
          .isFalse();
    }
    assertThat(EditionInterceptor.requiresCompanyEdition("/admin-api/kol-library/kols")).isTrue();
    assertThat(EditionInterceptor.requiresCompanyEdition("/admin-api/auth/resignation")).isTrue();
    assertThat(EditionInterceptor.requiresCompanyEdition("/kol-api/auth/login")).isFalse();
  }

  @Test
  void shouldDeclareAccess_whenExposingEndpoints() {
    endpointsDeclareAccess().check(classes);
  }

  @Test
  void shouldRejectEndpoint_whenAccessIsNotDeclared() {
    JavaClasses fixture = new ClassFileImporter().importClasses(UndeclaredEndpointFixture.class);
    assertThatThrownBy(() -> endpointsDeclareAccess().check(fixture))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("because access is denied by default");
  }

  private static ArchRule endpointsDeclareAccess() {
    return classes()
        .that()
        .areAnnotatedWith(RestController.class)
        .should(
            new ArchCondition<JavaClass>(
                "declare a permission, a role or public access for every endpoint") {
              @Override
              public void check(JavaClass type, ConditionEvents events) {
                for (var method : type.getMethods()) {
                  if (!method.isMetaAnnotatedWith(RequestMapping.class)
                      && !method.isAnnotatedWith(RequestMapping.class)) {
                    continue;
                  }
                  boolean declared =
                      type.isAnnotatedWith(SaCheckRole.class)
                          || type.isAnnotatedWith(SaCheckPermission.class)
                          || type.isAnnotatedWith(PublicEndpoint.class)
                          || method.isAnnotatedWith(SaCheckRole.class)
                          || method.isAnnotatedWith(SaCheckPermission.class)
                          || method.isAnnotatedWith(PublicEndpoint.class)
                          || declaresKolLogin(method);
                  events.add(new SimpleConditionEvent(method, declared, method.getFullName()));
                }
              }
            })
        .because(
            "access is denied by default (standard 06 §2, ADR-018); annotate the endpoint or its"
                + " controller with @SaCheckPermission or @SaCheckRole, @SaCheckLogin(type ="
                + " \"kol\") for KOL endpoints, or @PublicEndpoint for public ones");
  }

  /**
   * KOL endpoints declare access with {@code @SaCheckLogin(type = "kol")} (task 12a §4.2): the
   * annotation both documents the requirement and is enforced by the /kol-api/** interceptor.
   */
  private static boolean declaresKolLogin(JavaMethod method) {
    return method
        .tryGetAnnotationOfType(SaCheckLogin.class)
        .map(annotation -> StpKolUtil.TYPE.equals(annotation.type()))
        .orElse(false);
  }

  /**
   * Spring Framework 6.1+ validates {@code @Min}/{@code @Max} on controller parameters through
   * built-in MVC method validation ({@code HandlerMethodValidationException}). Class-level
   * {@code @Validated} switches to AOP method validation and can turn a 400 into a 500 in tests or
   * miss the shared field-error envelope — keep it off controllers.
   */
  @Test
  void shouldNotPutValidatedOnControllers() {
    noClasses()
        .that()
        .areAnnotatedWith(RestController.class)
        .should()
        .beAnnotatedWith(Validated.class)
        .because(
            "use Spring MVC built-in method validation; class-level @Validated is for @Service beans")
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
        .because(
            "public instance fields are forbidden (standard 02 §1); make the field private and"
                + " add a getter/setter, or use a record")
        .check(classes);
  }

  /**
   * {@code @TransactionalEventListener} drops events published outside a transaction. Only {@link
   * TransactionalEvents} may call {@link ApplicationEventPublisher#publishEvent}.
   */
  @Test
  void shouldPublishEventsOnlyThroughTransactionalEvents() {
    eventsPublishedOnlyThroughTransactionalEvents().check(classes);
  }

  @Test
  void shouldRejectDirectPublishEvent() {
    JavaClasses fixture = new ClassFileImporter().importClasses(DirectPublishFixture.class);
    assertThatThrownBy(() -> eventsPublishedOnlyThroughTransactionalEvents().check(fixture))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("because @TransactionalEventListener drops events");
  }

  private static ArchRule eventsPublishedOnlyThroughTransactionalEvents() {
    return classes()
        .that()
        .doNotHaveFullyQualifiedName(TransactionalEvents.class.getName())
        .should(
            new ArchCondition<JavaClass>("not call ApplicationEventPublisher.publishEvent") {
              @Override
              public void check(JavaClass type, ConditionEvents events) {
                for (var call : type.getMethodCallsFromSelf()) {
                  boolean publish =
                      call.getName().equals("publishEvent")
                          && call.getTargetOwner().isAssignableTo(ApplicationEventPublisher.class);
                  if (publish) {
                    events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                  }
                }
              }
            })
        .because(
            "@TransactionalEventListener drops events published outside a transaction; inject"
                + " shared.TransactionalEvents and publish through it");
  }
}
