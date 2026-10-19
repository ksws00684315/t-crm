package com.tcrm.admin.kol.service;

/** Fields of a self-registration after the controller has checked their shape (task 29). */
public record KolRegistration(
    String code,
    String email,
    String emailCode,
    String password,
    String name,
    String phone,
    String whatsapp,
    String primaryPlatform,
    String primaryHandle,
    String primaryUrl,
    String country) {}
