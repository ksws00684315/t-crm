package com.tcrm.admin.commission.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.KolMvcSupport;
import com.tcrm.admin.commission.model.PayoutAccountStatus;
import com.tcrm.admin.commission.model.PayoutAccountType;
import com.tcrm.admin.commission.model.UsdtNetwork;
import com.tcrm.admin.commission.service.BizFileService;
import com.tcrm.admin.commission.service.PayoutAccountService;
import com.tcrm.admin.commission.service.PrepayService;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.ClientIpResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Controller slice for the KOL-portal payout endpoints (task 15a §4.3). */
class KolPayoutAccountControllerTest {
  private static final String BASE = "/kol-api/payout-accounts";

  private final PayoutAccountService accounts = mock(PayoutAccountService.class);
  private final BizFileService files = mock(BizFileService.class);
  private final CurrentKolContext current = mock(CurrentKolContext.class);
  private final PrepayService prepays = mock(PrepayService.class);
  private final MockMvc mvc =
      KolMvcSupport.build(
          current,
          new KolPayoutAccountController(
              accounts, files, current, new ClientIpResolver("", "X-Forwarded-For"), prepays));

  private String token() {
    return KolMvcSupport.actAs(current, kol());
  }

  private static KolAccountDTO kol() {
    return new KolAccountDTO(
        9007199254740993L,
        "KOL0001",
        "Alice",
        "alice@example.com",
        "US",
        "hash",
        1,
        1,
        0,
        Instant.EPOCH,
        2L,
        "Acme",
        true);
  }

  private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static PayoutAccountService.KolAccountDTO dto(PayoutAccountStatus status) {
    return new PayoutAccountService.KolAccountDTO(
        77L,
        PayoutAccountType.USDT,
        "TQ5xnPW9tZ3kA7qL9aZ",
        null,
        null,
        UsdtNetwork.TRC20,
        55L,
        status,
        false,
        status == PayoutAccountStatus.REJECTED ? "blurry" : null,
        Instant.parse("2026-10-17T00:00:00Z"),
        null);
  }

  @Test
  void shouldListOwnAccountsWithFullValues() throws Exception {
    when(accounts.listMine(9007199254740993L))
        .thenReturn(List.of(dto(PayoutAccountStatus.PENDING)));
    mvc.perform(get(BASE).header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value("77"))
        .andExpect(jsonPath("$.data[0].account").value("TQ5xnPW9tZ3kA7qL9aZ"))
        .andExpect(jsonPath("$.data[0].qrFileId").value("55"))
        .andExpect(jsonPath("$.data[0].status").value("PENDING"));
  }

  @Test
  void shouldCreateWithNormalizedCommand() throws Exception {
    when(accounts.create(anyLong(), any(), any())).thenReturn(dto(PayoutAccountStatus.PENDING));
    mvc.perform(
            json(
                    post(BASE),
                    "{\"accountType\":\"USDT\",\"account\":\" TQ5xnPW9tZ3kA7qL9aZ \","
                        + "\"network\":\"TRC20\",\"qrFileId\":\"55\"}")
                .header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk());

    var command = ArgumentCaptor.forClass(PayoutAccountService.SaveCommand.class);
    verify(accounts).create(eq(9007199254740993L), command.capture(), any());
    assertThat(command.getValue().account()).isEqualTo("TQ5xnPW9tZ3kA7qL9aZ");
    assertThat(command.getValue().qrFileId()).isEqualTo(55L);
  }

  @Test
  void shouldReturn400_whenTypeFieldsAreMissingOrMalformed() throws Exception {
    String token = token();
    for (String body :
        List.of(
            "{\"accountType\":\"PAYPAL\",\"account\":\"alice@example.com\"}",
            "{\"accountType\":\"USDT\",\"account\":\"has space\",\"network\":\"TRC20\"}",
            "{\"accountType\":\"USDT\",\"account\":\"TQ5x9aZ1\"}",
            "{\"accountType\":\"PAYPAL\",\"account\":\"a\",\"holderName\":\"\"}",
            "{\"account\":\"alice@example.com\",\"holderName\":\"A\"}",
            "{\"accountType\":\"WECHAT\",\"account\":\"x123\"}")) {
      mvc.perform(json(post(BASE), body).header(KolMvcSupport.TOKEN_HEADER, token))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void shouldEditSetDefaultAndDisable() throws Exception {
    String token = token();
    mvc.perform(
            json(
                    put(BASE + "/77"),
                    "{\"accountType\":\"PAYPAL\",\"account\":\"alice@example.com\","
                        + "\"holderName\":\"Alice D\",\"currency\":\"USD\"}")
                .header(KolMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk());
    verify(accounts).update(eq(9007199254740993L), eq(77L), any(), any());
    mvc.perform(put(BASE + "/77/default").header(KolMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk());
    verify(accounts).setDefault(eq(9007199254740993L), eq(77L), any());
    mvc.perform(put(BASE + "/77/disable").header(KolMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isOk());
    verify(accounts).disable(eq(9007199254740993L), eq(77L), any());
  }

  @Test
  void shouldReturn401WithoutKolToken() throws Exception {
    mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
  }

  @Test
  void shouldUploadPayoutQr_andReturnFileId() throws Exception {
    when(files.uploadKolImage(anyLong(), any(), any(), any())).thenReturn(66L);
    mvc.perform(
            multipart("/kol-api/files")
                .file(new MockMultipartFile("file", "qr.png", "image/png", new byte[] {1}))
                .param("bizType", "PAYOUT_QR")
                .header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.fileId").value("66"));
  }

  @Test
  void shouldRejectOtherBizTypesForNow() throws Exception {
    mvc.perform(
            multipart("/kol-api/files")
                .file(new MockMultipartFile("file", "v.pdf", "application/pdf", new byte[] {1}))
                .param("bizType", "WITHDRAWAL_VOUCHER")
                .header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldDownloadOwnFileAsAttachment() throws Exception {
    when(files.attachedFile(66L))
        .thenReturn(
            new BizFileService.AttachedFile(
                com.tcrm.admin.commission.model.FileBizType.PAYOUT_QR, 77L, null));
    when(files.kolFile(9007199254740993L, 66L))
        .thenReturn(new BizFileService.FileDTO(66L, "二维码.png", "image/png", 8L, new byte[] {1, 2}));
    mvc.perform(get("/kol-api/files/66").header(KolMvcSupport.TOKEN_HEADER, token()))
        .andExpect(status().isOk())
        .andExpect(
            result ->
                assertThat(result.getResponse().getHeader("Content-Disposition"))
                    .contains("attachment")
                    // Non-ASCII names travel in the RFC 5987 parameter, percent-encoded.
                    .contains("filename*=UTF-8''%E4%BA%8C%E7%BB%B4%E7%A0%81.png"))
        .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).hasSize(2));
  }
}
