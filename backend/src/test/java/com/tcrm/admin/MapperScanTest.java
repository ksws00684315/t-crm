package com.tcrm.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;

/** Every @Mapper must be under a scanned package, or the application fails to start. */
class MapperScanTest {
  @Test
  void shouldScanEveryMapperPackage() {
    String[] scanned = TcrmAdminApplication.class.getAnnotation(MapperScan.class).value();
    var mappers =
        new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.tcrm.admin")
                .stream()
                .filter(c -> c.isAnnotatedWith(Mapper.class))
                .map(JavaClass::getName)
                .toList();
    assertThat(mappers).isNotEmpty();
    assertThat(mappers)
        .allSatisfy(
            name ->
                assertThat(Arrays.stream(scanned).anyMatch(p -> name.startsWith(p + ".")))
                    .as(name)
                    .isTrue());
  }
}
