package com.tcrm.admin.kol.service;

/** Fields of a self-registration after the controller has checked their shape. */
public record KolRegistration(
    String code,
    String email,
    String emailCode,
    String password,
    String whatsapp,
    String primaryPlatform,
    String primaryHandle,
    String primaryUrl,
    String name,
    String country) {}
