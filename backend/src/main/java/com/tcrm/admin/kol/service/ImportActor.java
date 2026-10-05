package com.tcrm.admin.kol.service;

/**
 * Submitter of a KOL import, captured on the request thread because the worker has no login state.
 *
 * @param own true for a BD (OPERATOR + OWN): imported KOLs are always assigned to the submitter
 */
record ImportActor(Long id, boolean own) {}
