package com.tcrm.admin.kol.controller;

/**
 * Answer of the super-administrator password reset (task 147): the generated password in plaintext,
 * returned once and never stored or logged.
 */
public record ResetKolPasswordVO(String password) {}
