package com.tcrm.admin.kollibrary.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.kollibrary.service.LibraryContentService;
import com.tcrm.admin.security.api.Edition;
import com.tcrm.admin.security.api.EditionScope;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Content records of one library creator (requirement 1.2.3, task 143 §3.1.6). They are addressed
 * under their creator, so a record id of another creator or company answers 404. The list is not
 * paged: a creator has few records (requirement 1.2.5 ③).
 */
@RestController
@RequestMapping("/admin-api/kol-library/kols/{kolId}/contents")
@EditionScope({Edition.KOL_LIBRARY})
public class KolLibraryContentController {
  private final LibraryContentService contents;

  public KolLibraryContentController(LibraryContentService contents) {
    this.contents = contents;
  }

  @GetMapping
  @SaCheckPermission("kol-library:view")
  public R<List<LibraryContentVO>> list(@PathVariable @Min(1) Long kolId) {
    return R.ok(contents.list(kolId).stream().map(LibraryContentVO::from).toList());
  }

  @PostMapping
  @SaCheckPermission("kol-library:edit")
  @AuditLog(module = "Kol library", action = "Create content")
  public R<LibraryContentVO> create(
      @PathVariable @Min(1) Long kolId, @Valid @RequestBody LibraryContentRequest r) {
    return R.ok(LibraryContentVO.from(contents.create(kolId, r.toCommand())));
  }

  @PutMapping("/{contentId}")
  @SaCheckPermission("kol-library:edit")
  @AuditLog(module = "Kol library", action = "Edit content")
  public R<LibraryContentVO> update(
      @PathVariable @Min(1) Long kolId,
      @PathVariable @Min(1) Long contentId,
      @Valid @RequestBody LibraryContentRequest r) {
    return R.ok(LibraryContentVO.from(contents.update(kolId, contentId, r.toCommand())));
  }

  /** Super administrators only (P1-15). */
  @DeleteMapping("/{contentId}")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "Kol library", action = "Delete content")
  public R<Void> delete(@PathVariable @Min(1) Long kolId, @PathVariable @Min(1) Long contentId) {
    contents.delete(kolId, contentId);
    return R.ok(null);
  }
}
