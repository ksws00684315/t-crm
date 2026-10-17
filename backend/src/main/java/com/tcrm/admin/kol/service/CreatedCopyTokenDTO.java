package com.tcrm.admin.kol.service;

/** Create response: one-time plaintext plus the list-row fields. */
public record CreatedCopyTokenDTO(String token, CopyTokenRowDTO row) {}
