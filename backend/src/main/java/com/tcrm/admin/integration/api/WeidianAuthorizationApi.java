package com.tcrm.admin.integration.api;

import java.util.List;

/** Credential-free authorization lookups within the current company. */
public interface WeidianAuthorizationApi {
  /** Shop IDs currently authorized; disabled shops must additionally be filtered by the caller. */
  List<Long> authorizedShopIds();
}
