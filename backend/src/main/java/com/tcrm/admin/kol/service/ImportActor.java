package com.tcrm.admin.kol.service;

/**
 * Submitter of a KOL import, captured on the request thread because the worker has no login state.
 *
 * @param own true for a staff account with OWN scope: imported KOLs are assigned to the submitter
 */
record ImportActor(Long id, boolean own) {}
