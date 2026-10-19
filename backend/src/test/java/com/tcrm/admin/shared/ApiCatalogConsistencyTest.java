package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Keeps design §6.2 / §6.3 in sync with controller mappings (task 23 §4.8). The machine-readable
 * rows live between {@code <!-- api-catalog:admin -->} / {@code <!-- api-catalog:kol -->} markers.
 */
class ApiCatalogConsistencyTest {
  private static final Pattern ROW =
      Pattern.compile("^\\|\\s*(GET|POST|PUT|DELETE|PATCH)\\s*\\|\\s*(/[^|\\s]+)\\s*\\|");

  @Test
  void shouldListEveryAdminEndpoint_inDesignSection62() throws Exception {
    assertSame(discover("/admin-api/"), parseDesign("api-catalog:admin"), "admin-api");
  }

  @Test
  void shouldListEveryKolEndpoint_inDesignSection63() throws Exception {
    assertSame(discover("/kol-api/"), parseDesign("api-catalog:kol"), "kol-api");
  }

  private static void assertSame(Set<String> code, Set<String> design, String label) {
    Set<String> missing = new LinkedHashSet<>(code);
    missing.removeAll(design);
    Set<String> extra = new LinkedHashSet<>(design);
    extra.removeAll(code);
    assertThat(missing)
        .as("%s endpoints in code but missing from design: %s", label, missing)
        .isEmpty();
    assertThat(extra)
        .as("%s endpoints in design but missing from code: %s", label, extra)
        .isEmpty();
  }

  private static Set<String> parseDesign(String marker) throws IOException {
    Path doc = Path.of("..", "docs", "03-设计", "系统详细设计说明书.md");
    assertThat(Files.isRegularFile(doc)).as("design doc at %s", doc.toAbsolutePath()).isTrue();
    String text = Files.readString(doc, StandardCharsets.UTF_8);
    String begin = "<!-- " + marker + " -->";
    String end = "<!-- /" + marker + " -->";
    int from = text.indexOf(begin);
    int to = text.indexOf(end);
    assertThat(from).as("missing begin marker %s", begin).isGreaterThanOrEqualTo(0);
    assertThat(to).as("missing end marker %s", end).isGreaterThan(from);
    Set<String> rows = new LinkedHashSet<>();
    for (String line : text.substring(from, to).split("\n")) {
      Matcher m = ROW.matcher(line.trim());
      if (m.find()) {
        rows.add(m.group(1) + " " + m.group(2));
      }
    }
    assertThat(rows).as("catalog %s is empty", marker).isNotEmpty();
    return rows;
  }

  private static Set<String> discover(String pathPrefix) throws Exception {
    Set<String> found = new LinkedHashSet<>();
    for (Class<?> type : controllerTypes()) {
      String typePath = firstPath(type.getAnnotation(RequestMapping.class));
      for (Method method : type.getDeclaredMethods()) {
        for (String mapping : methodMappings(method)) {
          String methodName = mapping.substring(0, mapping.indexOf(' '));
          String sub = mapping.substring(mapping.indexOf(' ') + 1);
          String full;
          if (sub.startsWith("/admin-api") || sub.startsWith("/kol-api") || sub.startsWith("/t/")) {
            full = sub;
          } else if (typePath.isEmpty()) {
            full = sub;
          } else if (sub.isEmpty()) {
            full = typePath;
          } else {
            full =
                (typePath.endsWith("/") ? typePath.substring(0, typePath.length() - 1) : typePath)
                    + (sub.startsWith("/") ? sub : "/" + sub);
          }
          if (full.endsWith("/") && full.length() > 1) {
            full = full.substring(0, full.length() - 1);
          }
          full = full.replaceAll("\\{([^}:]+):[^}]+}", "{$1}");
          if (full.startsWith(pathPrefix)) {
            found.add(methodName + " " + full);
          }
        }
      }
    }
    assertThat(found).as("no controllers for %s", pathPrefix).isNotEmpty();
    return found;
  }

  private static List<Class<?>> controllerTypes() throws Exception {
    Path root = Path.of("target/classes/com/tcrm/admin");
    assertThat(Files.isDirectory(root))
        .as("compiled classes at %s", root.toAbsolutePath())
        .isTrue();
    List<Class<?>> types = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path file :
          walk.filter(f -> f.getFileName().toString().endsWith("Controller.class")).toList()) {
        String rel = root.relativize(file).toString().replace('/', '.').replace('\\', '.');
        String name = "com.tcrm.admin." + rel.substring(0, rel.length() - ".class".length());
        Class<?> type = Class.forName(name);
        if (type.isAnnotationPresent(RestController.class)
            || type.isAnnotationPresent(Controller.class)) {
          types.add(type);
        }
      }
    }
    assertThat(types).as("no @RestController under target/classes").isNotEmpty();
    return types;
  }

  private static String firstPath(RequestMapping mapping) {
    if (mapping == null) {
      return "";
    }
    if (mapping.value().length > 0) {
      return mapping.value()[0];
    }
    if (mapping.path().length > 0) {
      return mapping.path()[0];
    }
    return "";
  }

  private static List<String> methodMappings(Method method) {
    List<String> out = new ArrayList<>();
    GetMapping get = method.getAnnotation(GetMapping.class);
    if (get != null) {
      out.add("GET " + firstOf(get.value(), get.path()));
    }
    PostMapping post = method.getAnnotation(PostMapping.class);
    if (post != null) {
      out.add("POST " + firstOf(post.value(), post.path()));
    }
    PutMapping put = method.getAnnotation(PutMapping.class);
    if (put != null) {
      out.add("PUT " + firstOf(put.value(), put.path()));
    }
    DeleteMapping del = method.getAnnotation(DeleteMapping.class);
    if (del != null) {
      out.add("DELETE " + firstOf(del.value(), del.path()));
    }
    PatchMapping patch = method.getAnnotation(PatchMapping.class);
    if (patch != null) {
      out.add("PATCH " + firstOf(patch.value(), patch.path()));
    }
    RequestMapping req = method.getAnnotation(RequestMapping.class);
    if (req != null && req.method().length > 0) {
      for (var m : req.method()) {
        out.add(m.name() + " " + firstPath(req));
      }
    }
    return out;
  }

  private static String firstOf(String[] value, String[] path) {
    if (value != null && value.length > 0) {
      return value[0];
    }
    if (path != null && path.length > 0) {
      return path[0];
    }
    return "";
  }
}
