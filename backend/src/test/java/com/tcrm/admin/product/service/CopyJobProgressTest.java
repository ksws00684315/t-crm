package com.tcrm.admin.product.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.product.mapper.CopyJobItemMapper;
import com.tcrm.admin.product.mapper.CopyJobMapper;
import com.tcrm.admin.product.model.CopyJobDO;
import com.tcrm.admin.product.model.CopyJobItemDO;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CopyJobProgressTest {
  @Test
  void shouldMarkPartialFailed_whenMixedSuccessAndFail() {
    CopyJobMapper jobs = mock(CopyJobMapper.class);
    CopyJobItemMapper items = mock(CopyJobItemMapper.class);
    CopyJobProgress progress = new CopyJobProgress(jobs, items);
    CopyJobDO job = new CopyJobDO();
    job.setId(1L);
    job.setStatus(CopyJobDO.STATUS_PROCESSING);
    when(jobs.selectById(1L)).thenReturn(job);
    CopyJobItemDO ok = new CopyJobItemDO();
    ok.setStatus(CopyJobItemDO.STATUS_SUCCESS);
    CopyJobItemDO bad = new CopyJobItemDO();
    bad.setStatus(CopyJobItemDO.STATUS_FAILED);
    when(items.listByJob(1L)).thenReturn(List.of(ok, bad));

    progress.recountAndMaybeFinish(1L);

    verify(jobs)
        .updateProgress(
            eq(1L), eq(1), eq(1), eq(CopyJobDO.STATUS_PARTIAL_FAILED), any(Instant.class));
  }

  @Test
  void shouldMarkFailed_whenAllFailed() {
    CopyJobMapper jobs = mock(CopyJobMapper.class);
    CopyJobItemMapper items = mock(CopyJobItemMapper.class);
    CopyJobProgress progress = new CopyJobProgress(jobs, items);
    CopyJobDO job = new CopyJobDO();
    job.setId(2L);
    when(jobs.selectById(2L)).thenReturn(job);
    CopyJobItemDO bad = new CopyJobItemDO();
    bad.setStatus(CopyJobItemDO.STATUS_FAILED);
    when(items.listByJob(2L)).thenReturn(List.of(bad));

    progress.recountAndMaybeFinish(2L);

    verify(jobs)
        .updateProgress(eq(2L), eq(0), eq(1), eq(CopyJobDO.STATUS_FAILED), any(Instant.class));
  }
}
