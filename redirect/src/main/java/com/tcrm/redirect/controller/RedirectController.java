package com.tcrm.redirect.controller;

import com.tcrm.redirect.service.RedirectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedirectController {
  private final RedirectService redirects;

  public RedirectController(RedirectService redirects) {
    this.redirects = redirects;
  }

  @GetMapping("/t/{kolCode}/{productCode}")
  public ResponseEntity<Void> redirect(
      @PathVariable String kolCode, @PathVariable String productCode, HttpServletRequest request) {
    return redirects.redirect(kolCode, productCode, request);
  }

  @GetMapping("/i/{weidianItemId}")
  public ResponseEntity<Void> redirectItem(
      @PathVariable String weidianItemId, HttpServletRequest request) {
    return redirects.redirectItem(weidianItemId, request);
  }

  @GetMapping("/healthz")
  public ResponseEntity<Void> health() {
    return redirects.health();
  }
}
