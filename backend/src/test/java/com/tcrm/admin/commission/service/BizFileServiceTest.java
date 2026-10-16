package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.commission.mapper.BizFileMapper;
import com.tcrm.admin.commission.model.BizFileDO;
import com.tcrm.admin.commission.model.FileBizType;
import com.tcrm.admin.shared.ErrorCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BizFileServiceTest {
  private static final long KOL = 10L;
  private static final byte[] PNG = {
    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00
  };

  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), BizFileDO.class);
  }

  private final BizFileMapper files = mock(BizFileMapper.class);
  private final BizFileService service = new BizFileService(files);

  private static BizFileDO file(Long bizId, String uploaderType, Long uploader) {
    BizFileDO file = new BizFileDO();
    file.setId(55L);
    file.setBizType(FileBizType.PAYOUT_QR);
    file.setBizId(bizId);
    file.setFileName("qr.png");
    file.setContentType("image/png");
    file.setSizeBytes(8L);
    file.setContent(new byte[8]);
    file.setCreateBy(uploader);
    file.setCreateByType(uploaderType);
    return file;
  }

  @Test
  void shouldStoreUploadWithDetectedTypeAndStableName() {
    when(files.insert(any(BizFileDO.class)))
        .thenAnswer(
            invocation -> {
              invocation.getArgument(0, BizFileDO.class).setId(66L);
              return 1;
            });

    assertThat(service.uploadKolImage(KOL, FileBizType.PAYOUT_QR, null, PNG)).isEqualTo(66L);

    var saved = ArgumentCaptor.forClass(BizFileDO.class);
    verify(files).insert(saved.capture());
    assertThat(saved.getValue().getContentType()).isEqualTo("image/png");
    assertThat(saved.getValue().getSizeBytes()).isEqualTo((long) PNG.length);
    assertThat(saved.getValue().getCreateByType()).isEqualTo("KOL");
    assertThat(saved.getValue().getBizId()).isNull();
    assertThat(saved.getValue().getFileName()).isEqualTo("upload.png");

    assertThat(service.uploadKolImage(KOL, FileBizType.PAYOUT_QR, "  x".repeat(200), PNG))
        .isEqualTo(66L);
    var both = ArgumentCaptor.forClass(BizFileDO.class);
    verify(files, org.mockito.Mockito.times(2)).insert(both.capture());
    assertThat(both.getAllValues().get(1).getFileName()).hasSize(255).endsWith("x");
  }

  @Test
  void shouldShowOwnUploadOnly() {
    when(files.selectById(55L)).thenReturn(file(null, "KOL", KOL));
    assertThat(service.kolFile(KOL, 55L).fileName()).isEqualTo("qr.png");

    when(files.selectById(55L)).thenReturn(file(null, "KOL", KOL + 1));
    assertThatThrownBy(() -> service.kolFile(KOL, 55L))
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
    when(files.selectById(55L)).thenReturn(file(null, "ADMIN", 1L));
    assertThatThrownBy(() -> service.kolFile(KOL, 55L))
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
    when(files.selectById(55L)).thenReturn(null);
    assertThatThrownBy(() -> service.kolFile(KOL, 55L))
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
  }

  @Test
  void should404MissingRows() {
    when(files.selectById(55L)).thenReturn(null);
    assertThatThrownBy(() -> service.file(55L)).hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
    assertThatThrownBy(() -> service.attachedFile(55L))
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
  }

  @Test
  void shouldClaimOnlyOwnUnusedQrUpload() {
    when(files.selectById(55L)).thenReturn(file(null, "KOL", KOL));
    service.claimQrFile(KOL, 55L, 77L);
    var wrapper = ArgumentCaptor.forClass(Wrapper.class);
    verify(files).update(isNull(), wrapper.capture());
    assertThat(
            ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper.getValue())
                .getSqlSegment())
        .contains("biz_id IS NULL");

    when(files.selectById(55L)).thenReturn(null);
    assertThatThrownBy(() -> service.claimQrFile(KOL, 55L, 77L))
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
    when(files.selectById(55L)).thenReturn(file(null, "KOL", KOL + 1));
    assertThatThrownBy(() -> service.claimQrFile(KOL, 55L, 77L))
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
    when(files.selectById(55L)).thenReturn(file(null, "ADMIN", 1L));
    assertThatThrownBy(() -> service.claimQrFile(KOL, 55L, 77L))
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());

    BizFileDO wrongType = file(null, "KOL", KOL);
    wrongType.setBizType(FileBizType.WITHDRAWAL_VOUCHER);
    when(files.selectById(55L)).thenReturn(wrongType);
    assertThatThrownBy(() -> service.claimQrFile(KOL, 55L, 77L))
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());

    when(files.selectById(55L)).thenReturn(file(99L, "KOL", KOL));
    assertThatThrownBy(() -> service.claimQrFile(KOL, 55L, 77L))
        .hasMessage(ErrorCode.FILE_IN_USE.message());
  }

  @Test
  void shouldCarryBusinessLinkOnAttachedFile() {
    when(files.selectById(55L)).thenReturn(file(77L, "KOL", KOL));
    BizFileService.AttachedFile attached = service.attachedFile(55L);
    assertThat(attached.bizType()).isEqualTo(FileBizType.PAYOUT_QR);
    assertThat(attached.bizId()).isEqualTo(77L);
    assertThat(attached.download().id()).isEqualTo(55L);
  }
}
