package com.tcrm.admin.kollibrary.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.kollibrary.service.LibraryKolService;
import com.tcrm.admin.security.api.Edition;
import com.tcrm.admin.security.api.EditionScope;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creator library of the creator-library edition (requirements 1.2, task 143 §3.1.6). Reading needs
 * {@code kol-library:view}, writing {@code kol-library:edit}; deleting a creator is a super
 * administrator action (P1-15). Accounts of a company see every creator (P1-13), so there is no own
 * scope here. A duplicate creator answers 409 KOL_LIBRARY_DUPLICATE with the existing id.
 */
@RestController
@RequestMapping("/admin-api/kol-library/kols")
@EditionScope({Edition.KOL_LIBRARY})
public class KolLibraryController {
  private final LibraryKolService kols;

  public KolLibraryController(LibraryKolService kols) {
    this.kols = kols;
  }

  @GetMapping
  @SaCheckPermission("kol-library:view")
  public R<PageResult<LibraryKolVO>> list(@Valid @ModelAttribute LibraryKolQuery q) {
    return R.ok(kols.page(q.toFilter()).map(LibraryKolVO::from));
  }

  @GetMapping("/{id}")
  @SaCheckPermission("kol-library:view")
  public R<LibraryKolDetailVO> get(@PathVariable @Min(1) Long id) {
    return R.ok(LibraryKolDetailVO.from(kols.get(id)));
  }

  @PostMapping
  @SaCheckPermission("kol-library:edit")
  @AuditLog(module = "Kol library", action = "Create")
  public R<LibraryKolDetailVO> create(@Valid @RequestBody LibraryKolRequest r) {
    return R.ok(LibraryKolDetailVO.from(kols.create(r.toCommand())));
  }

  @PutMapping("/{id}")
  @SaCheckPermission("kol-library:edit")
  @AuditLog(module = "Kol library", action = "Edit")
  public R<LibraryKolDetailVO> update(
      @PathVariable @Min(1) Long id, @Valid @RequestBody LibraryKolRequest r) {
    return R.ok(LibraryKolDetailVO.from(kols.update(id, r.toCommand())));
  }

  @PutMapping("/{id}/follow-up")
  @SaCheckPermission("kol-library:edit")
  @AuditLog(module = "Kol library", action = "Update follow-up")
  public R<LibraryKolDetailVO> followUp(
      @PathVariable @Min(1) Long id, @Valid @RequestBody LibraryFollowUpRequest r) {
    return R.ok(LibraryKolDetailVO.from(kols.updateFollowUp(id, r.toCommand())));
  }

  /** Deletes the creator and its content records; super administrators only (P1-15). */
  @DeleteMapping("/{id}")
  @SaCheckRole("SUPER_ADMIN")
  @AuditLog(module = "Kol library", action = "Delete")
  public R<Void> delete(@PathVariable @Min(1) Long id) {
    kols.delete(id);
    return R.ok(null);
  }
}
