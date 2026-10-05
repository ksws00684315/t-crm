package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.model.KolDO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class KolLookupServiceTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), KolDO.class);
  }

  @Test
  void shouldReturnEnabledFlag_fromStatus() {
    KolMapper kols = mock(KolMapper.class);
    KolDO enabled = new KolDO();
    enabled.setId(1L);
    enabled.setKolCode("KOL0001");
    enabled.setName("A");
    enabled.setStatus(1);
    when(kols.selectOne(any())).thenReturn(enabled);
    KolRefDTO dto = new KolLookupService(kols).getByCode("kol0001");
    assertThat(dto.enabled()).isTrue();
    assertThat(dto.kolCode()).isEqualTo("KOL0001");
  }

  @Test
  void shouldSkipUnknownIds_whenListing() {
    KolMapper kols = mock(KolMapper.class);
    when(kols.selectList(any())).thenReturn(List.of());
    assertThat(new KolLookupService(kols).listByIds(List.of(1L, 2L))).isEmpty();
  }

  @Test
  void shouldExposeBdUser_whenListingAllKols() {
    KolMapper kols = mock(KolMapper.class);
    KolDO kol = new KolDO();
    kol.setId(1L);
    kol.setKolCode("KOL0001");
    kol.setName("A");
    kol.setStatus(0);
    kol.setBdUserId(5L);
    when(kols.selectList(any())).thenReturn(List.of(kol));
    List<KolRefDTO> all = new KolLookupService(kols).listAll();
    assertThat(all).containsExactly(new KolRefDTO(1L, "KOL0001", "A", false, 5L));
  }
}
