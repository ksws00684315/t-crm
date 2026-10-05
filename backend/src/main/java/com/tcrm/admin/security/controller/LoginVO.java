package com.tcrm.admin.security.controller;

public record LoginVO(String tokenName, String token, long expiresIn, UserVO user) {}
